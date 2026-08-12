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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/// Orchestrateur d'enrichissement des salles (étapes 1-2).
///
/// Boucle sur les {@link SourceEnrichissementSalle} injectées (une nouvelle source = un bean
/// Spring, sans modification de ce service). L'ordre de déclaration des beans dans {@code
/// EnrichissementConfiguration} importe : {@code OverpassSalleSource} doit être déclaré avant
/// {@code WikidataSalleSource} — le Q-ID découvert par Overpass est propagé aux sources suivantes
/// dans la même passe pour que Wikidata puisse enrichir sur cette base. Pour les salles qu'Overpass
/// n'a pas trouvées (nom absent d'OSM, instance publique surchargée), {@code WikidataSalleSource}
/// tente ensuite sa propre recherche par nom — Overpass n'est donc plus un passage obligé.
///
/// Persistance par lot de {@link #TAILLE_LOT_PERSISTANCE} (et non en un seul bloc final) : avec
/// Overpass en incident de surcharge, un cycle complet peut prendre plusieurs dizaines de minutes
/// (chaque lot épuise son timeout avant d'abandonner) — persister uniquement à la toute fin
/// perdrait tout le travail déjà résolu en cas d'interruption (redéploiement, crash, arrêt du job)
/// avant la fin du cycle.
///
/// @since 2.6.0
/// @author Kwaite
@Service
public class EnrichissementSallesService {

  private static final Logger log = LoggerFactory.getLogger(EnrichissementSallesService.class);

  static final int TAILLE_LOT_PERSISTANCE = 25;
  // Délai entre deux lots de persistance, en plus du délai intra-source (500ms/appel côté
  // OverpassSalleSource et WikidataSalleSource) — laisse Overpass et Wikidata respirer entre deux
  // lots plutôt que d'enchaîner immédiatement, sur des instances publiques déjà sous tension.
  private static final long DELAI_ENTRE_LOTS_MS = 2000;

  private final NamedParameterJdbcTemplate jdbc;
  private final List<SourceEnrichissementSalle> sources;
  private final LogsAdminWorker logsAdminWorker;

  public EnrichissementSallesService(
      NamedParameterJdbcTemplate jdbc,
      List<SourceEnrichissementSalle> sources,
      LogsAdminWorker logsAdminWorker) {
    this.jdbc = jdbc;
    this.sources = sources;
    this.logsAdminWorker = logsAdminWorker;
  }

  /// Enrichit les salles sans {@code wikidata_id} dont {@code jour_batch} correspond au jour ISO
  /// courant (rotation hebdomadaire, — remplace l'ancien plafond fixe de 500/nuit, qui laissait une
  /// entité en échec systématique éternellement prioritaire, cf.), priorisées par nombre de
  /// concerts liés à l'intérieur de ce sous-ensemble. N'écrase jamais un {@code nom_affiche} déjà
  /// présent (override manuel admin).
  ///
  /// @return nombre de salles effectivement mises à jour
  public int enrichir() {
    List<SalleAEnrichir> candidats = selectionnerCandidats();
    if (candidats.isEmpty()) {
      log.info("enrichissement:salles — aucun candidat");
      return 0;
    }

    int nbEnrichis = 0;
    for (int i = 0; i < candidats.size(); i += TAILLE_LOT_PERSISTANCE) {
      if (i > 0) attendreEntreLots();
      List<SalleAEnrichir> lot =
          candidats.subList(i, Math.min(i + TAILLE_LOT_PERSISTANCE, candidats.size()));
      nbEnrichis += enrichirEtPersisterLot(lot);
    }
    log.info("enrichissement:salles candidats={} enrichis={}", candidats.size(), nbEnrichis);
    alerterSiCycleImproductif(candidats.size(), nbEnrichis);
    remonterBilans();
    return nbEnrichis;
  }

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

  /// Alerte si un cycle avec un nombre significatif de candidats a un taux de réussite chronique
  /// trop bas — un bilan d'appels/erreurs à 0 échec (voir {@link #remonterBilans}) ne détecte pas
  /// ce cas : une source répondant `200 OK` avec un contenu systématiquement vide (aucune
  /// correspondance, jamais une erreur HTTP) ne remonte jamais rien via ce mécanisme, alors qu'elle
  /// peut être structurellement inopérante. Un seuil strict à 0 salle enrichie (avant 2.7.9) ne
  /// détectait qu'une panne totale, jamais une dégradation chronique mais non totale (constaté en
  /// production : 90-100% d'échec chaque nuit durant 11 nuits, jamais 100% pile, restant ainsi
  /// indéfiniment sous le radar).
  ///
  /// @param nbCandidats nombre de salles traitées dans ce cycle
  /// @param nbEnrichis nombre de salles effectivement enrichies
  private void alerterSiCycleImproductif(int nbCandidats, int nbEnrichis) {
    if (nbCandidats < SEUIL_CANDIDATS_ALERTE_CYCLE_IMPRODUCTIF) return;
    double tauxReussite = (double) nbEnrichis / nbCandidats;
    if (tauxReussite >= SEUIL_TAUX_REUSSITE_MIN_CYCLE) return;
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

  /// Remonte dans {@code logs_admin} le bilan agrégé d'appels/erreurs de chaque source implémentant
  /// {@link SourceAvecBilanFetch} pour le cycle qui vient de se terminer — même mécanisme générique
  /// que {@code WorkerStatsService.remonterBilan}, absent jusqu'ici sur les sources
  /// d'enrichissement : la panne Overpass de 3+ mois n'avait ainsi jamais généré la moindre entrée
  /// {@code logs_admin}.
  private void remonterBilans() {
    for (SourceEnrichissementSalle source : sources) {
      if (!(source instanceof SourceAvecBilanFetch avecBilan)) continue;
      BilanFetch bilan = avecBilan.preleverBilan();
      bilan
          .evaluer(
              "reconciliation:nocturne",
              capitaliser(source.nom()),
              BilanFetch.SEUIL_APPELS_MIN_ALERTE_DEFAUT)
          .ifPresent(
              entree ->
                  logsAdminWorker.inserer(
                      entree.niveau(), "worker", entree.message(), entree.meta()));
    }
  }

  private static String capitaliser(String nom) {
    return nom.isEmpty() ? nom : Character.toUpperCase(nom.charAt(0)) + nom.substring(1);
  }

  private static void attendreEntreLots() {
    try {
      Thread.sleep(DELAI_ENTRE_LOTS_MS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private int enrichirEtPersisterLot(List<SalleAEnrichir> lot) {
    Map<UUID, EnrichissementSalle> accumule = new HashMap<>();
    List<SalleAEnrichir> courant = lot;
    for (SourceEnrichissementSalle source : sources) {
      Map<UUID, EnrichissementSalle> resultat = source.enrichirLot(courant);
      for (var entree : resultat.entrySet()) {
        accumule.merge(entree.getKey(), entree.getValue(), EnrichissementSallesService::fusionner);
      }
      courant = appliquerWikidataIdConnu(courant, accumule);
    }

    int nbEnrichis = 0;
    for (SalleAEnrichir salle : lot) {
      EnrichissementSalle enrichissement = accumule.get(salle.id());
      if (enrichissement == null) continue;
      if (mettreAJourSalle(salle.id(), salle.nom(), enrichissement)) nbEnrichis++;
    }
    return nbEnrichis;
  }

  private List<SalleAEnrichir> selectionnerCandidats() {
    return jdbc.query(
        """
        SELECT s.id, s.nom, s.nom_norm, s.ville, s.dept
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
                rs.getString("dept"),
                null));
  }

  private static List<SalleAEnrichir> appliquerWikidataIdConnu(
      List<SalleAEnrichir> candidats, Map<UUID, EnrichissementSalle> accumule) {
    return candidats.stream()
        .map(
            s -> {
              EnrichissementSalle e = accumule.get(s.id());
              String qid = e != null && e.wikidataId() != null ? e.wikidataId() : s.wikidataId();
              return new SalleAEnrichir(s.id(), s.nom(), s.nomNorm(), s.ville(), s.dept(), qid);
            })
        .toList();
  }

  private static EnrichissementSalle fusionner(EnrichissementSalle a, EnrichissementSalle b) {
    return new EnrichissementSalle(
        b.wikidataId() != null ? b.wikidataId() : a.wikidataId(),
        b.nomAffiche() != null ? b.nomAffiche() : a.nomAffiche(),
        b.ville() != null ? b.ville() : a.ville(),
        b.website() != null ? b.website() : a.website(),
        b.capacite() != null ? b.capacite() : a.capacite());
  }

  /// @return {@code true} si la mise à jour a été appliquée, {@code false} si le {@code wikidataId}
  ///   découvert est déjà porté par une autre salle (contrainte {@code idx_salles_wikidata_id}) —
  ///   signe d'un faux positif de correspondance ou d'un doublon non encore fusionné; le conflit
  ///   est loggé plutôt que de faire échouer tout le cycle de réconciliation.
  private boolean mettreAJourSalle(UUID salleId, String nom, EnrichissementSalle e) {
    Map<String, Object> params = new HashMap<>();
    params.put("wikidataId", e.wikidataId());
    params.put("nomAffiche", e.nomAffiche());
    params.put("ville", e.ville());
    params.put("id", salleId.toString());
    try {
      jdbc.update(
          """
          UPDATE salles SET
            wikidata_id = COALESCE(wikidata_id, :wikidataId),
            nom_affiche = COALESCE(nom_affiche, :nomAffiche),
            ville       = COALESCE(ville, :ville)
          WHERE id = CAST(:id AS uuid)
          """,
          params);
      return true;
    } catch (DuplicateKeyException ex) {
      logsAdminWorker.inserer(
          "warn",
          "enrichissement-wikidata",
          "wikidataId déjà attribué à une autre salle — conflit ignoré salle="
              + nom
              + " wikidataId="
              + e.wikidataId(),
          Map.of("salleId", salleId.toString(), "wikidataId", e.wikidataId()));
      return false;
    }
  }
}
