/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker.persister;

import fr.strapontin.core.CorrespondanceArtiste;
import fr.strapontin.worker.LogsAdminWorker;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Détecte et fusionne en cycle nocturne des paires d'événements déjà
 * persistés séparément : dont l'{@code artiste_norm} converge après coup —
 * {@link ReconciliateurArtistes} ne dédoublonne qu'à la création — ou dont
 * l'un n'est plus qu'une fiche périmée de l'autre (même fiche source, l'une
 * n'étant plus observée, l'autre l'étant encore).
 * @since 3.0.0
 * @author Kwaite
 */
@Service
public class FusionEvenementsArtisteService {

  private static final Logger LOG = LoggerFactory.getLogger(FusionEvenementsArtisteService.class);
  private static final String CLE_CIBLE = "cible";
  private static final String CLE_SOURCE = "source";
  private static final int NB_EVENEMENTS_PAIRE = 2;
  // Absences consécutives au-delà desquelles un événement n'est plus une fiche vivante : écarte
  // un cycle de fetch manqué ponctuellement.
  /* package-private : visibilité élargie pour les tests (même package). */
  static final int SEUIL_ABSENCES_FICHE_PERIMEE = 3;

  private final NamedParameterJdbcTemplate jdbc;
  private final LogsAdminWorker logsAdminWorker;

  private record PaireEvenements(UUID idA, UUID idB) {}

  /**
   * @param jdbc accès JDBC
   * @param logsAdminWorker journalisation de chaque fusion effectuée
   */
  public FusionEvenementsArtisteService(
      final NamedParameterJdbcTemplate jdbc, final LogsAdminWorker logsAdminWorker) {
    this.jdbc = jdbc;
    this.logsAdminWorker = logsAdminWorker;
  }

  /**
   * Détecte puis fusionne les paires d'événements partageant salle/date/
   * heure avec un {@code artiste_norm} doublon flou — une paire déjà
   * disparue (fusionnée plus tôt dans le même cycle) est ignorée
   * silencieusement (redondante).
   * @return nombre de fusions effectuées
   */
  public int fusionner() {
    final List<PaireEvenements> paires = detecterPaires();
    final Set<UUID> dejaAbsorbes = new HashSet<>();
    int fusions = 0;
    for (final PaireEvenements paire : paires) {
      if (dejaAbsorbes.contains(paire.idA()) || dejaAbsorbes.contains(paire.idB())) {
        continue;
      }
      final UUID[] cibleSource = choisirCibleEtSource(paire.idA(), paire.idB());
      if (cibleSource == null) {
        continue;
      }
      fusionnerUnePaire(cibleSource[0], cibleSource[1]);
      dejaAbsorbes.add(cibleSource[1]);
      fusions++;
    }
    return fusions;
  }

  /**
   * Fusionne chaque événement périmé dans l'événement vivant qui porte la
   * même fiche source : même observation {@code (source, url)}, ou même
   * manifestation Ticketmaster ({@code idmanif}) à la même date. Couvre une
   * fiche rattachée à la mauvaise salle puis recréée correctement, et un
   * report publié sous une autre URL ; sans fusion, la ligne figée reste
   * suivie et visible jusqu'à sa purge, avec ses informations obsolètes. Un
   * événement périmé rapproché de plusieurs événements vivants est ignoré
   * (ambigu). À exécuter avant la fusion de salles par clé forte, que ces
   * doublons fausseraient.
   * @return nombre de fusions effectuées
   */
  public int fusionnerFichesPerimees() {
    final List<Map<String, Object>> lignes =
        jdbc.queryForList(
            """
            WITH obs AS (
              SELECT o.evenement_id, o.source, o.url, e.date, e.absences_consecutives,
                     CASE WHEN o.source = 'ticketmaster'
                          THEN substring(o.url FROM '/idmanif/([0-9]+)') END AS idmanif
              FROM observations o
              JOIN evenements e ON e.id = o.evenement_id
              WHERE o.url IS NOT NULL AND e.date >= CURRENT_DATE
            )
            SELECT p.evenement_id AS perime, min(v.evenement_id::text) AS vivant,
                   count(DISTINCT v.evenement_id) AS nb_vivants
            FROM obs p
            JOIN obs v
              ON v.evenement_id <> p.evenement_id
             AND v.source = p.source
             AND (v.url = p.url OR (p.idmanif IS NOT NULL AND v.idmanif = p.idmanif
                                    AND v.date = p.date))
            WHERE p.absences_consecutives >= :seuil AND v.absences_consecutives = 0
            GROUP BY p.evenement_id
            """,
            Map.of("seuil", SEUIL_ABSENCES_FICHE_PERIMEE));
    int fusions = 0;
    for (final Map<String, Object> ligne : lignes) {
      final UUID perime = (UUID) ligne.get("perime");
      if (((Number) ligne.get("nb_vivants")).longValue() != 1) {
        LOG.warn("fiche périmée {} rapprochée de plusieurs événements vivants — ignorée", perime);
        continue;
      }
      fusionner(UUID.fromString((String) ligne.get("vivant")), perime, "fiche source périmée");
      fusions++;
    }
    return fusions;
  }

  /**
   * Repère les paires d'{@code evenement_id} distincts partageant
   * exactement même salle/date/heure, filtrées par
   * {@link CorrespondanceArtiste#estDoublonArtiste} sur les
   * {@code artiste_norm} — la contrainte unique {@code uq_evenements}
   * garantit déjà qu'aucune paire ne partage un {@code artiste_norm}
   * strictement identique.
   */
  private List<PaireEvenements> detecterPaires() {
    final List<Map<String, Object>> lignes =
        jdbc.queryForList(
            """
            SELECT DISTINCT e1.id AS evt_a, e2.id AS evt_b,
                   e1.artiste_norm AS artiste_a, e2.artiste_norm AS artiste_b
            FROM evenements e1
            JOIN evenements e2
              ON e1.salle_id = e2.salle_id
             AND e1.date = e2.date
             AND e1.heure IS NOT DISTINCT FROM e2.heure
             AND e1.artiste_norm <> e2.artiste_norm
             AND e1.id < e2.id
            WHERE e1.salle_id IS NOT NULL
              AND e1.date >= CURRENT_DATE
            """,
            Map.of());
    final List<PaireEvenements> paires = new ArrayList<>();
    for (final Map<String, Object> ligne : lignes) {
      final String artisteA = (String) ligne.get("artiste_a");
      final String artisteB = (String) ligne.get("artiste_b");
      if (!CorrespondanceArtiste.estDoublonArtiste(artisteA, artisteB)) {
        continue;
      }
      paires.add(new PaireEvenements((UUID) ligne.get("evt_a"), (UUID) ligne.get("evt_b")));
    }
    return paires;
  }

  /**
   * Choisit l'événement à conserver (cible) entre les deux membres de la
   * paire : priorité à l'{@code artiste_id} déjà résolu (donnée coûteuse
   * à reconstituer), puis au nombre d'observations déjà rattachées, puis
   * à l'UUID le plus petit pour un résultat déterministe entre deux
   * cycles.
   * @return [cible, source], ou {@code null} si l'un des deux n'existe plus
   */
  // Le tableau représente une paire cible/source, pas une collection à agréger
  // — l'appelant teste explicitement null pour distinguer "événement disparu"
  // d'un tableau vide, sémantiques différentes.
  @SuppressWarnings("PMD.ReturnEmptyCollectionRatherThanNull")
  private UUID[] choisirCibleEtSource(final UUID idA, final UUID idB) {
    final List<Map<String, Object>> lignes =
        jdbc.queryForList(
            """
            SELECT e.id AS id, e.artiste_id AS artiste_id,
                   (SELECT COUNT(*) FROM observations o WHERE o.evenement_id = e.id) AS nb_obs
            FROM evenements e WHERE e.id IN (CAST(:a AS uuid), CAST(:b AS uuid))
            """,
            Map.of("a", idA.toString(), "b", idB.toString()));
    if (lignes.size() < NB_EVENEMENTS_PAIRE) {
      return null;
    }

    final Map<String, Object> la =
        lignes.get(0).get("id").equals(idA) ? lignes.get(0) : lignes.get(1);
    final Map<String, Object> lb =
        lignes.get(0).get("id").equals(idA) ? lignes.get(1) : lignes.get(0);

    final boolean aArtisteId = la.get("artiste_id") != null;
    final boolean bArtisteId = lb.get("artiste_id") != null;
    if (aArtisteId != bArtisteId) {
      return aArtisteId ? new UUID[] {idA, idB} : new UUID[] {idB, idA};
    }

    final long nbA = ((Number) la.get("nb_obs")).longValue();
    final long nbB = ((Number) lb.get("nb_obs")).longValue();
    if (nbA != nbB) {
      return nbA > nbB ? new UUID[] {idA, idB} : new UUID[] {idB, idA};
    }

    return idA.compareTo(idB) <= 0 ? new UUID[] {idA, idB} : new UUID[] {idB, idA};
  }

  /**
   * Fusionne deux événements établis (réassigne observations/user_matches,
   * récupère l'{@code artiste_id} manquant, supprime la source). Public :
   * réutilisable pour une remédiation ponctuelle ciblée.
   * @param cibleId événement à conserver
   * @param sourceId événement à absorber puis supprimer
   */
  @Transactional
  public void fusionnerUnePaire(final UUID cibleId, final UUID sourceId) {
    fusionner(cibleId, sourceId, "artiste_norm convergent");
  }

  private void fusionner(final UUID cibleId, final UUID sourceId, final String motif) {
    final Map<String, Object> ids =
        Map.of(CLE_CIBLE, cibleId.toString(), CLE_SOURCE, sourceId.toString());

    // 1. Observations en conflit supprimées, le reste réassigné à la
    // cible.
    jdbc.update(
        """
        DELETE FROM observations o1
        USING observations o2
        WHERE o1.evenement_id = CAST(:source AS uuid) AND o2.evenement_id = CAST(:cible AS uuid)
          AND o1.source = o2.source AND o1.url IS NOT DISTINCT FROM o2.url
        """,
        ids);
    jdbc.update(
        "UPDATE observations SET evenement_id = CAST(:cible AS uuid)"
            + " WHERE evenement_id = CAST(:source AS uuid)",
        ids);

    // 2. user_matches : conflits déjà côté cible supprimés, le reste
    // réassigné — jamais laissé à la suppression CASCADE de la source
    // (perdrait un match déjà notifié).
    jdbc.update(
        """
        DELETE FROM user_matches um1
        USING user_matches um2
        WHERE um1.concert_id = CAST(:source AS uuid) AND um2.concert_id = CAST(:cible AS uuid)
          AND um1.user_id = um2.user_id
        """,
        ids);
    jdbc.update(
        "UPDATE user_matches SET concert_id = CAST(:cible AS uuid)"
            + " WHERE concert_id = CAST(:source AS uuid)",
        ids);

    // 3. artiste_id manquant récupéré depuis la source si la cible ne l'a pas déjà.
    jdbc.update(
        """
        UPDATE evenements SET artiste_id = COALESCE(artiste_id,
            (SELECT artiste_id FROM evenements WHERE id = CAST(:source AS uuid)))
        WHERE id = CAST(:cible AS uuid)
        """,
        ids);

    // 4. Suppression de la source (CASCADE sur observations/
    // user_matches restants, déjà vidés par les étapes précédentes).
    jdbc.update("DELETE FROM evenements WHERE id = CAST(:source AS uuid)", ids);

    LOG.info(
        "reconciliation:nocturne — événements fusionnés ({}) cible={} source={}",
        motif,
        cibleId,
        sourceId);
    // Fusion automatique et destructive : jamais silencieuse.
    logsAdminWorker.inserer(
        "info",
        "reconciliation-nocturne",
        "événements fusionnés automatiquement ("
            + motif
            + ") cible="
            + cibleId
            + " source="
            + sourceId,
        Map.of(
            "evenementCible",
            cibleId.toString(),
            "evenementSource",
            sourceId.toString(),
            "motif",
            motif));
  }
}
