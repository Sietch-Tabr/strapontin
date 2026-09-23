/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker.persister;

import fr.strapontin.core.CorrespondanceSalle;
import fr.strapontin.core.Normalisation;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Retente la résolution des concerts orphelins ({@code salle_id IS NULL}) déjà
 * persistés — extrait de {@link ReconciliateurSalles} (v2.8.7, dette de taille
 * pure, aucun changement fonctionnel).
 *
 * @since 2.8.7
 * @author Kwaite
 */
@Service
class ReconciliateurSallesOrphelines {

  private static final Logger LOG = LoggerFactory.getLogger(ReconciliateurSallesOrphelines.class);

  private final NamedParameterJdbcTemplate jdbc;
  private final FusionEvenementsArtisteService fusionEvenementsArtisteService;

  /* package-private : instancié uniquement par Spring (même package). */
  ReconciliateurSallesOrphelines(
      final NamedParameterJdbcTemplate jdbc,
      final FusionEvenementsArtisteService fusionEvenementsArtisteService) {
    this.jdbc = jdbc;
    this.fusionEvenementsArtisteService = fusionEvenementsArtisteService;
  }

  /**
   * Couvre le résidu structurel laissé par {@code upsertSalleSource}
   * (détail : CLAUDE.md, Hiérarchie des salles).
   * @return nombre de concerts orphelins résolus
   */
  /* package-private : appelé par le cycle nocturne du même package. */
  int retenterSallesOrphelines() {
    final List<Map<String, Object>> orphelins =
        jdbc.queryForList(
            "SELECT id, salle, dept, artiste_norm, date, heure FROM concerts"
                + " WHERE salle_id IS NULL AND date >= CURRENT_DATE",
            Map.of());
    if (orphelins.isEmpty()) {
      return 0;
    }

    // candidats chargés une seule fois (pas une requête SQL par orphelin) — le
    // volume de salles reste compatible avec un score en mémoire, contrairement
    // au N+1 SQL que remplace cette méthode.
    final List<CandidatSalleMatching> candidats = chargerCandidatsMatching();

    int resolus = 0;
    for (final Map<String, Object> orphelin : orphelins) {
      final String salle = (String) orphelin.get("salle");
      final String dept = (String) orphelin.get("dept");
      final UUID salleId = meilleurCandidatEnMemoire(salle, dept, candidats);
      if (salleId == null) {
        continue;
      }
      final UUID orphelinId = (UUID) orphelin.get("id");
      final UUID doublonExistant = chercherDoublonExistant(orphelinId, salleId, orphelin);
      if (doublonExistant != null) {
        // la salle candidate ferait doublon avec un événement déjà établi
        // (même artiste_norm/date/heure) — fusionner plutôt que de laisser
        // l'UPDATE violer la contrainte unique uq_evenements.
        fusionEvenementsArtisteService.fusionnerUnePaire(doublonExistant, orphelinId);
      } else {
        jdbc.update(
            "UPDATE evenements SET salle_id = CAST(:salleId AS uuid) WHERE id = :id",
            Map.of("salleId", salleId.toString(), "id", orphelinId));
      }
      resolus++;
    }
    if (resolus > 0) {
      LOG.info(
          "réconciliation salles orphelines candidats={} résolus={}", orphelins.size(), resolus);
    }
    return resolus;
  }

  /**
   * Détecte un conflit avec {@code uq_evenements} avant l'UPDATE
   * (incident réel v3.0.0 : {@code DuplicateKeyException}).
   * @param orphelinId événement orphelin en cours de résolution
   * @param salleId salle candidate retenue pour cet orphelin
   * @param orphelin ligne brute de l'orphelin (artiste_norm/date/heure)
   * @return identifiant de l'événement doublon, ou {@code null} si aucun
   */
  private UUID chercherDoublonExistant(
      final UUID orphelinId, final UUID salleId, final Map<String, Object> orphelin) {
    // HashMap (pas Map.of) : heure est nullable, Map.of rejette toute
    // valeur null dès sa construction.
    final Map<String, Object> params = new HashMap<>();
    params.put("salleId", salleId.toString());
    params.put("date", orphelin.get("date"));
    params.put("heure", orphelin.get("heure"));
    params.put("artisteNorm", orphelin.get("artiste_norm"));
    params.put("orphelinId", orphelinId.toString());
    final List<UUID> doublons =
        jdbc.query(
            """
            SELECT id FROM evenements
            WHERE salle_id = CAST(:salleId AS uuid) AND date = :date
              AND heure IS NOT DISTINCT FROM :heure AND artiste_norm = :artisteNorm
              AND id <> CAST(:orphelinId AS uuid)
            """,
            params,
            (rs, _) -> rs.getObject("id", UUID.class));
    return doublons.isEmpty() ? null : doublons.get(0);
  }

  /**
   * Salle candidate pour {@link #meilleurCandidatEnMemoire} —
   * {@code deptEffectif} incluant déjà le repli sur le département d'un concert
   * déjà rattaché ({@code salles.dept} n'étant écrit qu'à la création).
   */
  private record CandidatSalleMatching(UUID id, String nomNorm, String deptEffectif) {}

  /**
   * Charge en une seule requête l'ensemble des salles utilisables comme
   * candidats de rapprochement par nom.
   *
   * @return candidats de toutes les salles connues
   */
  private List<CandidatSalleMatching> chargerCandidatsMatching() {
    return jdbc.query(
        "SELECT s.id AS id, s.nom_norm AS nom_norm,"
            + " COALESCE(s.dept, (SELECT c.dept FROM concerts c"
            + "                   WHERE c.salle_id = s.id AND c.dept IS NOT NULL LIMIT 1))"
            + "   AS dept_effectif"
            + " FROM salles s",
        Map.of(),
        (rs, _) ->
            new CandidatSalleMatching(
                rs.getObject("id", UUID.class),
                rs.getString("nom_norm"),
                rs.getString("dept_effectif")));
  }

  /**
   * Équivalent en mémoire de {@code chercherSalleParTokens} (détail :
   * CLAUDE.md, Hiérarchie des salles).
   * @param nom nom brut de la salle à rapprocher
   * @param dept département connu (nullable — désactive le filtre)
   * @param candidats toutes les salles connues, préchargées
   * @return UUID de la meilleure salle candidate, ou {@code null}
   */
  private UUID meilleurCandidatEnMemoire(
      final String nom, final String dept, final List<CandidatSalleMatching> candidats) {
    final String nomNorm = Normalisation.normaliserTexte(nom);
    UUID meilleurUuid = null;
    double meilleurScore = -1;
    for (final CandidatSalleMatching candidat : candidats) {
      if (dept != null
          && candidat.deptEffectif() != null
          && !candidat.deptEffectif().equals(dept)) {
        continue;
      }
      if (!CorrespondanceSalle.estDoublonSalle(nomNorm, candidat.nomNorm())) {
        continue;
      }
      final double score = CorrespondanceSalle.jaccardTokens(nomNorm, candidat.nomNorm());
      if (score > meilleurScore) {
        meilleurScore = score;
        meilleurUuid = candidat.id();
      }
    }
    return meilleurUuid;
  }
}
