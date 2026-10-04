/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker.enrichissement;

import fr.strapontin.core.BilanFetch;
import fr.strapontin.core.NomsDeLieu;
import fr.strapontin.sources.EnrichissementSalle;
import fr.strapontin.sources.SalleAEnrichir;
import fr.strapontin.sources.SourceAvecAmbiguitesDecouverte;
import fr.strapontin.sources.SourceAvecBilanFetch;
import fr.strapontin.sources.SourceEnrichissementSalle;
import fr.strapontin.worker.LogsAdminWorker;
import fr.strapontin.worker.SuiviChroniciteService;
import fr.strapontin.worker.persister.FusionSallesClePorteeService;
import fr.strapontin.worker.sante.SignalementMatchingService;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Orchestrateur d'enrichissement des salles (étapes 1-2), boucle sur les
 * {@link SourceEnrichissementSalle} injectées — ordre de déclaration des
 * beans et persistance par lot : CLAUDE.md, Enrichissement Wikidata/OSM.
 * @since 2.6.0
 * @author Kwaite
 */
@Service
public class EnrichissementSallesService {

  private static final Logger LOG = LoggerFactory.getLogger(EnrichissementSallesService.class);

  private static final int TAILLE_LOT_PERSISTANCE = 25;
  // Délai entre deux lots de persistance, en plus du délai intra-source
  // (cadenceurs de Nominatim et de WikidataSalleSource) — laisse ces services
  // publics respirer entre deux lots plutôt que d'enchaîner immédiatement.
  private static final long DELAI_ENTRE_LOTS_MS = 2000;
  // Nombre minimal de candidats pour qu'un cycle sous le seuil de taux de
  // réussite déclenche une alerte — évite un faux positif sur un tout petit lot
  // où un faible nombre de succès est plausible même en fonctionnement normal
  // (aucune des quelques salles du jour n'existe sur Wikidata/OSM).
  private static final int SEUIL_CANDIDATS_ALERTE_CYCLE_IMPRODUCTIF = 20;
  // Un seuil strict à 0 salle enrichie ne détecte qu'une panne totale — une
  // dégradation chronique mais non totale (constaté en production : 90-100%
  // d'échec chaque nuit durant 11 nuits, jamais 100% pile) reste alors
  // indéfiniment sous le seuil. Un taux de réussite est requis, pas seulement
  // une absence totale de résultat.
  private static final double SEUIL_TAUX_REUSSITE_MIN_CYCLE = 0.15;
  private static final String MOTIF_ALERTE_TAUX_BAS =
      "enrichissement:salles — taux de réussite chronique trop bas";
  private static final int JOURS_ENTRE_ALERTES_TAUX_BAS = 7;
  private static final String CLE_WIKIDATA_ID = "wikidataId";
  private static final String CLE_LATITUDE = "latitude";
  private static final String CLE_LONGITUDE = "longitude";
  private static final String CLE_VILLE = "ville";
  private static final String CLE_NOM_NORM = "nom_norm";

  private final NamedParameterJdbcTemplate jdbc;
  private final List<SourceEnrichissementSalle> sources;
  private final LogsAdminWorker logsAdminWorker;
  private final SuiviChroniciteService suiviChronicite;
  private final FusionSallesClePorteeService fusionSallesClePortee;
  private final SignalementMatchingService signalementMatchingService;

  /**
   * @param jdbc accès JDBC
   * @param sources sources d'enrichissement, dans leur ordre de déclaration
   * @param logsAdminWorker journalisation du bilan de cycle
   * @param suiviChronicite comptage des cycles consécutifs en échec par source
   * @param fusionSallesClePortee fusion nocturne des salles détectées comme doublons
   * @param signalementMatchingService journalisation des ambiguïtés de découverte
   */
  public EnrichissementSallesService(
      final NamedParameterJdbcTemplate jdbc,
      final List<SourceEnrichissementSalle> sources,
      final LogsAdminWorker logsAdminWorker,
      final SuiviChroniciteService suiviChronicite,
      final FusionSallesClePorteeService fusionSallesClePortee,
      final SignalementMatchingService signalementMatchingService) {
    this.jdbc = jdbc;
    this.sources = sources;
    this.logsAdminWorker = logsAdminWorker;
    this.suiviChronicite = suiviChronicite;
    this.fusionSallesClePortee = fusionSallesClePortee;
    this.signalementMatchingService = signalementMatchingService;
  }

  /**
   * Enrichit les salles sans {@code wikidata_id} du {@code jour_batch}
   * courant (rotation hebdomadaire), priorisées par nombre de concerts
   * liés — n'écrase jamais un {@code nom_affiche} déjà présent.
   * @return nombre de salles effectivement mises à jour
   */
  public int enrichir() {
    final List<SalleAEnrichir> candidats = selectionnerCandidats();
    if (candidats.isEmpty()) {
      LOG.info("enrichissement:salles — aucun candidat");
      return 0;
    }

    int nbEnrichis = 0;
    for (int i = 0; i < candidats.size(); i += TAILLE_LOT_PERSISTANCE) {
      if (i > 0) {
        EnrichissementUtils.attendreEntreLots(DELAI_ENTRE_LOTS_MS);
      }
      final List<SalleAEnrichir> lot =
          candidats.subList(i, Math.min(i + TAILLE_LOT_PERSISTANCE, candidats.size()));
      nbEnrichis += enrichirEtPersisterLot(lot);
    }
    LOG.info("enrichissement:salles candidats={} enrichis={}", candidats.size(), nbEnrichis);
    alerterSiCycleImproductif(candidats.size(), nbEnrichis);
    remonterBilans();
    return nbEnrichis;
  }

  /**
   * Alerte sur un taux de réussite chronique trop bas (voir {@link
   * #SEUIL_CANDIDATS_ALERTE_CYCLE_IMPRODUCTIF}/{@link
   * #SEUIL_TAUX_REUSSITE_MIN_CYCLE}) — {@link #remonterBilans} ne détecte
   * pas une source répondant 200 OK avec un contenu vide.
   * @param nbCandidats nombre de salles traitées dans ce cycle
   * @param nbEnrichis nombre de salles effectivement enrichies
   */
  private void alerterSiCycleImproductif(final int nbCandidats, final int nbEnrichis) {
    if (nbCandidats < SEUIL_CANDIDATS_ALERTE_CYCLE_IMPRODUCTIF) {
      return;
    }
    final double tauxReussite = (double) nbEnrichis / nbCandidats;
    if (tauxReussite >= SEUIL_TAUX_REUSSITE_MIN_CYCLE) {
      return;
    }
    // Les salles restant à enrichir sont souvent les cas difficiles : rappel
    // hebdomadaire plutôt que chaque nuit.
    if (alerteTauxBasRecente()) {
      LOG.info(
          "enrichissement:salles — taux bas {}/{}, déjà signalé cette semaine",
          nbEnrichis,
          nbCandidats);
      return;
    }
    logsAdminWorker.inserer(
        "warn",
        "reconciliation-nocturne",
        MOTIF_ALERTE_TAUX_BAS
            + " ("
            + nbEnrichis
            + "/"
            + nbCandidats
            + ") — vérifier que les sources d'enrichissement répondent réellement",
        Map.of("candidats", nbCandidats, "enrichis", nbEnrichis));
  }

  private boolean alerteTauxBasRecente() {
    final Boolean recente =
        jdbc.queryForObject(
            """
            SELECT EXISTS (SELECT 1 FROM logs_admin
                           WHERE source = 'reconciliation-nocturne' AND niveau = 'warn'
                             AND message LIKE :motif
                             AND ts > now() - make_interval(days => :jours))
            """,
            Map.of("motif", MOTIF_ALERTE_TAUX_BAS + "%", "jours", JOURS_ENTRE_ALERTES_TAUX_BAS),
            Boolean.class);
    return Boolean.TRUE.equals(recente);
  }

  /**
   * Remonte dans {@code logs_admin} le bilan agrégé d'appels/erreurs de chaque
   * source implémentant {@link SourceAvecBilanFetch} pour le cycle qui vient de
   * se terminer — même mécanisme que {@code WorkerStatsService.remonterBilan}
   * : une panne de plus de 3 mois d'une source publique n'avait laissé
   * aucune trace dans {@code logs_admin}.
   */
  private void remonterBilans() {
    for (final SourceEnrichissementSalle source : sources) {
      if (!(source instanceof SourceAvecBilanFetch avecBilan)) {
        continue;
      }
      final BilanFetch bilan = avecBilan.preleverBilan();
      if (bilan.appels() == 0) {
        continue;
      }
      final int streak =
          suiviChronicite.enregistrerCycle(
              "reconciliation:nocturne|" + EnrichissementUtils.capitaliser(source.nom()),
              bilan.erreursHttp() > 0);
      bilan
          .evaluer(
              "reconciliation:nocturne",
              EnrichissementUtils.capitaliser(source.nom()),
              avecBilan.seuilAppelsMinAlerte())
          .ifPresent(
              entree ->
                  logsAdminWorker.inserer(
                      entree.niveau(),
                      "worker",
                      suiviChronicite.enrichirSiChronique(entree.message(), streak),
                      entree.meta()));
    }
    remonterAmbiguites();
  }

  /**
   * Signale chaque salle dont un candidat Wikidata a été rejeté sur
   * divergence GPS depuis le dernier cycle — un nom/ville/dept compatibles
   * mais un bâtiment différent (ex. Alhambra Paris, deux arrondissements)
   * mérite une revue manuelle plutôt qu'une non-découverte silencieuse.
   */
  private void remonterAmbiguites() {
    for (final SourceEnrichissementSalle source : sources) {
      if (!(source instanceof SourceAvecAmbiguitesDecouverte avecAmbiguites)) {
        continue;
      }
      for (final UUID salleId : avecAmbiguites.preleverAmbiguites()) {
        signalementMatchingService.signaler(
            "wikidata_coordonnees_divergentes", "salle", salleId, "basse", null);
      }
    }
  }

  private int enrichirEtPersisterLot(final List<SalleAEnrichir> lot) {
    final Map<UUID, EnrichissementSalle> accumule = new HashMap<>();
    List<SalleAEnrichir> courant = lot;
    for (final SourceEnrichissementSalle source : sources) {
      final Map<UUID, EnrichissementSalle> resultat = source.enrichirLot(courant);
      for (final Map.Entry<UUID, EnrichissementSalle> entree : resultat.entrySet()) {
        accumule.merge(entree.getKey(), entree.getValue(), EnrichissementSallesService::fusionner);
      }
      courant = appliquerWikidataIdConnu(courant, accumule);
    }

    int nbEnrichis = 0;
    for (final SalleAEnrichir salle : lot) {
      final EnrichissementSalle enrichissement = accumule.get(salle.id());
      if (enrichissement == null) {
        continue;
      }
      if (mettreAJourSalle(salle.id(), salle.nom(), enrichissement)) {
        nbEnrichis++;
      }
    }
    return nbEnrichis;
  }

  // Repli sur le département d'un concert déjà rattaché quand salles.dept est
  // vide — même pattern que ResolveurSalleParConcert (module worker.persister),
  // sans quoi une salle sans ville ni dept renseignés n'a aucun signal de
  // localisation pour écarter un homonyme Wikidata d'une autre ville (#1188).
  // Position géocodée exclue : celle d'un homonyme rejetait les bons candidats.
  private List<SalleAEnrichir> selectionnerCandidats() {
    return jdbc.query(
        """
        SELECT s.id, s.nom, s.nom_norm, s.ville,
               CASE WHEN s.origine_position = 'geocodage' THEN NULL ELSE s.latitude END
                 AS latitude,
               CASE WHEN s.origine_position = 'geocodage' THEN NULL ELSE s.longitude END
                 AS longitude,
               COALESCE(s.dept, (SELECT c2.dept FROM concerts c2
                                 WHERE c2.salle_id = s.id AND c2.dept IS NOT NULL
                                 LIMIT 1)) AS dept_effectif
        FROM salles s
        LEFT JOIN concerts c ON c.salle_id = s.id AND c.date >= CURRENT_DATE
        WHERE s.wikidata_id IS NULL AND s.jour_batch = EXTRACT(ISODOW FROM CURRENT_DATE)
        GROUP BY s.id, s.nom, s.nom_norm, s.ville, s.dept, s.latitude, s.longitude,
                 s.origine_position
        ORDER BY COUNT(c.id) DESC
        """,
        Map.of(),
        (rs, _) ->
            new SalleAEnrichir(
                rs.getObject("id", UUID.class),
                rs.getString("nom"),
                rs.getString(CLE_NOM_NORM),
                rs.getString(CLE_VILLE),
                rs.getString("dept_effectif"),
                null,
                (Double) rs.getObject(CLE_LATITUDE),
                (Double) rs.getObject(CLE_LONGITUDE)));
  }

  private static List<SalleAEnrichir> appliquerWikidataIdConnu(
      final List<SalleAEnrichir> candidats, final Map<UUID, EnrichissementSalle> accumule) {
    return candidats.stream()
        .map(
            s -> {
              final EnrichissementSalle enrichissement = accumule.get(s.id());
              final String qid =
                  enrichissement != null && enrichissement.wikidataId() != null
                      ? enrichissement.wikidataId()
                      : s.wikidataId();
              return new SalleAEnrichir(
                  s.id(),
                  s.nom(),
                  s.nomNorm(),
                  s.ville(),
                  s.dept(),
                  qid,
                  s.latitude(),
                  s.longitude());
            })
        .toList();
  }

  private static EnrichissementSalle fusionner(
      final EnrichissementSalle existant, final EnrichissementSalle nouveau) {
    return new EnrichissementSalle(
        nouveau.wikidataId() != null ? nouveau.wikidataId() : existant.wikidataId(),
        nouveau.nomAffiche() != null ? nouveau.nomAffiche() : existant.nomAffiche(),
        nouveau.ville() != null ? nouveau.ville() : existant.ville(),
        nouveau.website() != null ? nouveau.website() : existant.website(),
        nouveau.capacite() != null ? nouveau.capacite() : existant.capacite(),
        nouveau.latitude() != null ? nouveau.latitude() : existant.latitude(),
        nouveau.longitude() != null ? nouveau.longitude() : existant.longitude());
  }

  /**
   * @return {@code true} si la mise à jour a été appliquée (directement, ou via
   *     une fusion automatique en cas de collision {@code wikidata_id} avec
   *     une autre salle déjà établie), {@code false} si le conflit n'a pu être
   *     résolu automatiquement — auquel cas il est signalé ({@link
   *     SignalementMatchingService}) pour revue manuelle plutôt que de faire
   *     échouer tout le cycle de réconciliation.
   */
  private boolean mettreAJourSalle(
      final UUID salleId, final String nom, final EnrichissementSalle enrichissement) {
    final Map<String, Object> params = new HashMap<>();
    params.put(CLE_WIKIDATA_ID, enrichissement.wikidataId());
    params.put("nomAffiche", enrichissement.nomAffiche());
    params.put(CLE_VILLE, enrichissement.ville());
    params.put(CLE_LATITUDE, enrichissement.latitude());
    params.put(CLE_LONGITUDE, enrichissement.longitude());
    params.put("id", salleId.toString());
    try {
      jdbc.update(
          """
          UPDATE salles SET
            wikidata_id = COALESCE(wikidata_id, :wikidataId),
            nom_affiche = COALESCE(nom_affiche, :nomAffiche),
            ville       = COALESCE(ville, :ville),
            latitude    = COALESCE(CAST(:latitude AS double precision), latitude),
            longitude   = COALESCE(CAST(:longitude AS double precision), longitude),
            origine_position = CASE WHEN CAST(:latitude AS double precision) IS NOT NULL
                                    THEN 'wikidata' ELSE origine_position END
          WHERE id = CAST(:id AS uuid)
          """,
          params);
      return true;
    } catch (DuplicateKeyException ex) {
      return gererCollisionWikidataId(salleId, nom, enrichissement);
    }
  }

  /**
   * Collision {@code wikidata_id} : fusion automatique seulement si les noms
   * désignent fortement le même lieu et si la salle porteuse est positionnée
   * par ses lieux publiés, proche de la nôtre ; sinon signalement seul
   * (CLAUDE.md, signalements_matching).
   */
  private boolean gererCollisionWikidataId(
      final UUID salleId, final String nom, final EnrichissementSalle enrichissement) {
    final Map<String, Object> porteuse = trouverPorteuseWikidataId(enrichissement.wikidataId());
    if (porteuse != null && tenterFusionParCoordonnees(salleId, porteuse)) {
      LOG.info(
          "collision wikidata_id résolue par fusion automatique salle={} wikidataId={} cible={}",
          nom,
          enrichissement.wikidataId(),
          porteuse.get("id"));
      return true;
    }

    logsAdminWorker.inserer(
        "warn",
        "enrichissement-wikidata",
        "wikidataId déjà attribué à une autre salle — conflit ignoré salle="
            + nom
            + " wikidataId="
            + enrichissement.wikidataId(),
        Map.of("salleId", salleId.toString(), CLE_WIKIDATA_ID, enrichissement.wikidataId()));
    if (porteuse != null) {
      signalementMatchingService.signaler(
          "collision_wikidata_salle",
          "salle",
          salleId,
          "basse",
          Map.of(
              CLE_WIKIDATA_ID,
              enrichissement.wikidataId(),
              "salleExistante",
              porteuse.get("id").toString()));
    }
    return false;
  }

  // Retour null idiomatique (une entité unique trouvée ou non, pas une collection
  // de plusieurs éléments) — Map.of() introduirait une ambiguïté avec une salle
  // réellement vide de coordonnées.
  @SuppressWarnings("PMD.ReturnEmptyCollectionRatherThanNull")
  private Map<String, Object> trouverPorteuseWikidataId(final String wikidataId) {
    final List<Map<String, Object>> lignes =
        jdbc.queryForList(
            "SELECT id, nom_norm, ville, latitude, longitude, origine_position FROM salles"
                + " WHERE wikidata_id = :wikidataId",
            Map.of(CLE_WIKIDATA_ID, wikidataId));
    return lignes.isEmpty() ? null : lignes.get(0);
  }

  // Une porteuse positionnée par Wikidata est toujours proche de la salle qui
  // reçoit le même élément : le Théâtre Montparnasse, porteur à tort de
  // l'élément du Théâtre de Poche, a absorbé ce dernier.
  private boolean tenterFusionParCoordonnees(
      final UUID salleId, final Map<String, Object> porteuse) {
    final List<Map<String, Object>> lignes =
        jdbc.queryForList(
            "SELECT nom_norm, ville, latitude, longitude FROM salles WHERE id = CAST(:id AS uuid)",
            Map.of("id", salleId.toString()));
    if (lignes.isEmpty() || !"sources".equals(porteuse.get("origine_position"))) {
      return false;
    }
    final Map<String, Object> salle = lignes.get(0);
    final String contexteVilles =
        Objects.toString(salle.get(CLE_VILLE), "")
            + " "
            + Objects.toString(porteuse.get(CLE_VILLE), "");
    if (!NomsDeLieu.designentFortementLeMemeLieu(
            (String) salle.get(CLE_NOM_NORM), (String) porteuse.get(CLE_NOM_NORM), contexteVilles)
        || !FusionSallesClePorteeService.coordonneesConnuesEtProches(
            (Double) salle.get(CLE_LATITUDE),
            (Double) salle.get(CLE_LONGITUDE),
            (Double) porteuse.get(CLE_LATITUDE),
            (Double) porteuse.get(CLE_LONGITUDE))) {
      return false;
    }
    fusionSallesClePortee.fusionnerUnePaire((UUID) porteuse.get("id"), salleId);
    return true;
  }
}
