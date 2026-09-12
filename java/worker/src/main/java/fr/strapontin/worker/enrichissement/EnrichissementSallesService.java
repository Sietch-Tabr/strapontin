/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker.enrichissement;

import fr.strapontin.core.BilanFetch;
import fr.strapontin.sources.EnrichissementSalle;
import fr.strapontin.sources.SalleAEnrichir;
import fr.strapontin.sources.SourceAvecBilanFetch;
import fr.strapontin.sources.SourceEnrichissementSalle;
import fr.strapontin.worker.LogsAdminWorker;
import fr.strapontin.worker.SuiviChroniciteService;
import fr.strapontin.worker.persister.FusionSallesClePorteeService;
import fr.strapontin.worker.sante.SignalementMatchingService;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Orchestrateur d'enrichissement des salles (étapes 1-2).
 *
 * <p>Boucle sur les {@link SourceEnrichissementSalle} injectées (une nouvelle source = un bean
 * Spring, sans modification de ce service). L'ordre de déclaration des beans dans {@code
 * EnrichissementConfiguration} importe : {@code OverpassSalleSource} doit être déclaré avant {@code
 * WikidataSalleSource} — le Q-ID découvert par Overpass est propagé aux sources suivantes dans la
 * même passe pour que Wikidata puisse enrichir sur cette base. Pour les salles qu'Overpass n'a pas
 * trouvées (nom absent d'OSM, instance publique surchargée), {@code WikidataSalleSource} tente
 * ensuite sa propre recherche par nom — Overpass n'est donc plus un passage obligé.
 *
 * <p>Persistance par lot de {@link #TAILLE_LOT_PERSISTANCE} (et non en un seul bloc final) : avec
 * Overpass en incident de surcharge, un cycle complet peut prendre plusieurs dizaines de minutes
 * (chaque lot épuise son timeout avant d'abandonner) — persister uniquement à la toute fin perdrait
 * tout le travail déjà résolu en cas d'interruption (redéploiement, crash, arrêt du job) avant la
 * fin du cycle.
 *
 * @since 2.6.0
 * @author Kwaite
 */
@Service
public class EnrichissementSallesService {

  private static final Logger LOG = LoggerFactory.getLogger(EnrichissementSallesService.class);

  static final int TAILLE_LOT_PERSISTANCE = 25;
  // Délai entre deux lots de persistance, en plus du délai intra-source (500ms/appel côté
  // OverpassSalleSource et WikidataSalleSource) — laisse Overpass et Wikidata respirer entre deux
  // lots plutôt que d'enchaîner immédiatement, sur des instances publiques déjà sous tension.
  private static final long DELAI_ENTRE_LOTS_MS = 2000;
  // Nombre minimal de candidats pour qu'un cycle sous le seuil de taux de réussite déclenche une
  // alerte — évite un faux positif sur un tout petit lot où un faible nombre de succès est
  // plausible même en fonctionnement normal (aucune des quelques salles du jour n'existe sur
  // Wikidata/OSM).
  private static final int SEUIL_CANDIDATS_ALERTE_CYCLE_IMPRODUCTIF = 20;
  // Un seuil strict à 0 salle enrichie ne détecte qu'une panne totale — une dégradation
  // chronique mais non totale (constaté en production : 90-100% d'échec chaque nuit durant 11
  // nuits, jamais 100% pile) reste alors indéfiniment sous le seuil. Un taux de réussite est
  // requis, pas seulement une absence totale de résultat.
  private static final double SEUIL_TAUX_REUSSITE_MIN_CYCLE = 0.15;

  private final NamedParameterJdbcTemplate jdbc;
  private final List<SourceEnrichissementSalle> sources;
  private final LogsAdminWorker logsAdminWorker;
  private final SuiviChroniciteService suiviChronicite;
  private final FusionSallesClePorteeService fusionSallesClePortee;
  private final SignalementMatchingService signalementMatchingService;

  /**
   * @param jdbc accès JDBC
   * @param sources sources d'enrichissement injectées, dans leur ordre de déclaration Spring
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
   * Enrichit les salles sans {@code wikidata_id} dont {@code jour_batch} correspond au jour ISO
   * courant (rotation hebdomadaire, — remplace l'ancien plafond fixe de 500/nuit, qui laissait une
   * entité en échec systématique éternellement prioritaire, cf.), priorisées par nombre de concerts
   * liés à l'intérieur de ce sous-ensemble. N'écrase jamais un {@code nom_affiche} déjà présent
   * (override manuel admin).
   *
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
   * Alerte si un cycle avec un nombre significatif de candidats a un taux de réussite chronique
   * trop bas — un bilan d'appels/erreurs à 0 échec (voir {@link #remonterBilans}) ne détecte pas ce
   * cas : une source répondant {@code 200 OK} avec un contenu systématiquement vide (aucune
   * correspondance, jamais une erreur HTTP) ne remonte jamais rien via ce mécanisme, alors qu'elle
   * peut être structurellement inopérante. Un seuil strict à 0 salle enrichie (avant 2.7.9) ne
   * détectait qu'une panne totale, jamais une dégradation chronique mais non totale (constaté en
   * production : 90-100% d'échec chaque nuit durant 11 nuits, jamais 100% pile, restant ainsi
   * indéfiniment sous le radar).
   *
   * @param nbCandidats nombre de salles traitées dans ce cycle
   * @param nbEnrichis nombre de salles effectivement enrichies
   */
  private void alerterSiCycleImproductif(int nbCandidats, int nbEnrichis) {
    if (nbCandidats < SEUIL_CANDIDATS_ALERTE_CYCLE_IMPRODUCTIF) {
      return;
    }
    final double tauxReussite = (double) nbEnrichis / nbCandidats;
    if (tauxReussite >= SEUIL_TAUX_REUSSITE_MIN_CYCLE) {
      return;
    }
    logsAdminWorker.inserer(
        "warn",
        "reconciliation-nocturne",
        "enrichissement:salles — taux de réussite chronique trop bas ("
            + nbEnrichis
            + "/"
            + nbCandidats
            + ") — vérifier que les sources d'enrichissement répondent réellement",
        Map.of("candidats", nbCandidats, "enrichis", nbEnrichis));
  }

  /**
   * Remonte dans {@code logs_admin} le bilan agrégé d'appels/erreurs de chaque source implémentant
   * {@link SourceAvecBilanFetch} pour le cycle qui vient de se terminer — même mécanisme générique
   * que {@code WorkerStatsService.remonterBilan}, absent jusqu'ici sur les sources d'enrichissement
   * : la panne Overpass de 3+ mois n'avait ainsi jamais généré la moindre entrée {@code
   * logs_admin}.
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

  // Repli sur le département d'un concert déjà rattaché quand salles.dept est vide — même pattern
  // que ResolveurSalleParConcert (module worker.persister), sans quoi une salle sans ville ni dept
  // renseignés n'a aucun signal de localisation pour écarter un homonyme Wikidata d'une autre
  // ville (#1188).
  private List<SalleAEnrichir> selectionnerCandidats() {
    return jdbc.query(
        """
        SELECT s.id, s.nom, s.nom_norm, s.ville,
               COALESCE(s.dept, (SELECT c2.dept FROM concerts c2
                                 WHERE c2.salle_id = s.id AND c2.dept IS NOT NULL
                                 LIMIT 1)) AS dept_effectif
        FROM salles s
        LEFT JOIN concerts c ON c.salle_id = s.id AND c.date >= CURRENT_DATE
        WHERE s.wikidata_id IS NULL AND s.jour_batch = EXTRACT(ISODOW FROM CURRENT_DATE)
        GROUP BY s.id, s.nom, s.nom_norm, s.ville, s.dept
        ORDER BY COUNT(c.id) DESC
        """,
        Map.of(),
        (rs, _) ->
            new SalleAEnrichir(
                rs.getObject("id", UUID.class),
                rs.getString("nom"),
                rs.getString("nom_norm"),
                rs.getString("ville"),
                rs.getString("dept_effectif"),
                null));
  }

  private static List<SalleAEnrichir> appliquerWikidataIdConnu(
      final List<SalleAEnrichir> candidats, final Map<UUID, EnrichissementSalle> accumule) {
    return candidats.stream()
        .map(
            s -> {
              final EnrichissementSalle e = accumule.get(s.id());
              final String qid =
                  e != null && e.wikidataId() != null ? e.wikidataId() : s.wikidataId();
              return new SalleAEnrichir(s.id(), s.nom(), s.nomNorm(), s.ville(), s.dept(), qid);
            })
        .toList();
  }

  private static EnrichissementSalle fusionner(
      final EnrichissementSalle a, final EnrichissementSalle b) {
    return new EnrichissementSalle(
        b.wikidataId() != null ? b.wikidataId() : a.wikidataId(),
        b.nomAffiche() != null ? b.nomAffiche() : a.nomAffiche(),
        b.ville() != null ? b.ville() : a.ville(),
        b.website() != null ? b.website() : a.website(),
        b.capacite() != null ? b.capacite() : a.capacite(),
        b.latitude() != null ? b.latitude() : a.latitude(),
        b.longitude() != null ? b.longitude() : a.longitude());
  }

  /**
   * @return {@code true} si la mise à jour a été appliquée (directement, ou via une fusion
   *     automatique en cas de collision {@code wikidata_id} avec une autre salle déjà établie),
   *     {@code false} si le conflit n'a pu être résolu automatiquement — auquel cas il est signalé
   *     ({@link SignalementMatchingService}) pour revue manuelle plutôt que de faire échouer tout
   *     le cycle de réconciliation.
   */
  private boolean mettreAJourSalle(
      final UUID salleId, final String nom, final EnrichissementSalle e) {
    final Map<String, Object> params = new HashMap<>();
    params.put("wikidataId", e.wikidataId());
    params.put("nomAffiche", e.nomAffiche());
    params.put("ville", e.ville());
    params.put("latitude", e.latitude());
    params.put("longitude", e.longitude());
    params.put("id", salleId.toString());
    try {
      jdbc.update(
          """
          UPDATE salles SET
            wikidata_id = COALESCE(wikidata_id, :wikidataId),
            nom_affiche = COALESCE(nom_affiche, :nomAffiche),
            ville       = COALESCE(ville, :ville),
            latitude    = COALESCE(latitude, :latitude),
            longitude   = COALESCE(longitude, :longitude)
          WHERE id = CAST(:id AS uuid)
          """,
          params);
      return true;
    } catch (DuplicateKeyException ex) {
      return gererCollisionWikidataId(salleId, nom, e);
    }
  }

  /**
   * Une collision {@code wikidata_id} est un signal plus fort qu'une simple coïncidence de concert
   * (Wikidata affirme explicitement l'identité) — traité comme {@link
   * FusionSallesClePorteeService}, avec le même garde-fou GPS, mais fusion automatique uniquement
   * si les deux salles ont des coordonnées connues et proches (#1189) : à la différence d'une
   * fusion par clé forte, aucun autre signal positif (nom, dept) ne cross-valide cette collision,
   * l'absence de preuve géographique doit donc empêcher la fusion plutôt que l'autoriser par
   * défaut.
   */
  private boolean gererCollisionWikidataId(
      final UUID salleId, final String nom, final EnrichissementSalle e) {
    final Map<String, Object> porteuse = trouverPorteuseWikidataId(e.wikidataId());
    if (porteuse != null && tenterFusionParCoordonnees(salleId, porteuse)) {
      LOG.info(
          "collision wikidata_id résolue par fusion automatique salle={} wikidataId={} cible={}",
          nom,
          e.wikidataId(),
          porteuse.get("id"));
      return true;
    }

    logsAdminWorker.inserer(
        "warn",
        "enrichissement-wikidata",
        "wikidataId déjà attribué à une autre salle — conflit ignoré salle="
            + nom
            + " wikidataId="
            + e.wikidataId(),
        Map.of("salleId", salleId.toString(), "wikidataId", e.wikidataId()));
    if (porteuse != null) {
      signalementMatchingService.signaler(
          "collision_wikidata_salle",
          "salle",
          salleId,
          "basse",
          Map.of("wikidataId", e.wikidataId(), "salleExistante", porteuse.get("id").toString()));
    }
    return false;
  }

  private Map<String, Object> trouverPorteuseWikidataId(final String wikidataId) {
    final List<Map<String, Object>> lignes =
        jdbc.queryForList(
            "SELECT id, latitude, longitude FROM salles WHERE wikidata_id = :wikidataId",
            Map.of("wikidataId", wikidataId));
    return lignes.isEmpty() ? null : lignes.get(0);
  }

  private boolean tenterFusionParCoordonnees(
      final UUID salleId, final Map<String, Object> porteuse) {
    final List<Map<String, Object>> lignes =
        jdbc.queryForList(
            "SELECT latitude, longitude FROM salles WHERE id = CAST(:id AS uuid)",
            Map.of("id", salleId.toString()));
    if (lignes.isEmpty()) {
      return false;
    }
    final Double latSalle = (Double) lignes.get(0).get("latitude");
    final Double lonSalle = (Double) lignes.get(0).get("longitude");
    final Double latPorteuse = (Double) porteuse.get("latitude");
    final Double lonPorteuse = (Double) porteuse.get("longitude");
    if (!FusionSallesClePorteeService.coordonneesConnuesEtProches(
        latSalle, lonSalle, latPorteuse, lonPorteuse)) {
      return false;
    }
    fusionSallesClePortee.fusionnerUnePaire((UUID) porteuse.get("id"), salleId);
    return true;
  }
}
