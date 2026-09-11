/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker.persister;

import fr.strapontin.core.CorrespondanceSalle;
import fr.strapontin.core.Normalisation;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Retente la résolution des concerts orphelins ({@code salle_id IS NULL}) déjà persistés — extrait de
 * {@link ReconciliateurSalles} (v2.8.7, dette de taille pure, aucun changement fonctionnel).
 *
 * @since 2.8.7
 * @author Kwaite
 */
@Service
class ReconciliateurSallesOrphelines {

  private static final Logger log = LoggerFactory.getLogger(ReconciliateurSallesOrphelines.class);

  private final NamedParameterJdbcTemplate jdbc;

  ReconciliateurSallesOrphelines(NamedParameterJdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * Couvre le résidu structurel laissé par {@code upsertSalleSource} ({@link
   * ResolveurSalleParConcert}) : un concert sans {@code salleSourceId} (TM omet parfois {@code
   * venue.id}) et sans salle suffisamment proche par nom au moment de son insertion reste orphelin
   * indéfiniment, sans jamais être retenté — la salle correspondante peut pourtant apparaître plus
   * tard via une autre source (un autre événement TM avec {@code venue.id} renseigné, Eventim,
   * Digitick). Appelé une fois par nuit (voir {@code ReconciliationNocturneService}) plutôt qu'à
   * chaque cycle de fetch : le volume concerné reste faible (quelques dizaines de concerts) et une
   * résolution par nom n'a pas de raison de réussir plusieurs fois par jour si elle a échoué la
   * veille.
   *
   * @return nombre de concerts orphelins résolus
   */
  int retenterSallesOrphelines() {
    List<Map<String, Object>> orphelins =
        jdbc.queryForList(
            "SELECT id, salle, dept FROM concerts"
                + " WHERE salle_id IS NULL AND date >= CURRENT_DATE",
            Map.of());
    if (orphelins.isEmpty()) return 0;

    // candidats chargés une seule fois (pas une requête SQL par orphelin) — le volume de salles
    // reste compatible avec un score en mémoire, contrairement au N+1 SQL que remplace cette
    // méthode.
    List<CandidatSalleMatching> candidats = chargerCandidatsMatching();

    int resolus = 0;
    for (Map<String, Object> orphelin : orphelins) {
      String salle = (String) orphelin.get("salle");
      String dept = (String) orphelin.get("dept");
      UUID salleId = meilleurCandidatEnMemoire(salle, dept, candidats);
      if (salleId == null) continue;
      jdbc.update(
          "UPDATE evenements SET salle_id = CAST(:salleId AS uuid) WHERE id = :id",
          Map.of("salleId", salleId.toString(), "id", orphelin.get("id")));
      resolus++;
    }
    if (resolus > 0) {
      log.info(
          "réconciliation salles orphelines candidats={} résolus={}", orphelins.size(), resolus);
    }
    return resolus;
  }

  /**
   * Salle candidate pour {@link #meilleurCandidatEnMemoire} — {@code deptEffectif} incluant déjà
   * le repli sur le département d'un concert déjà rattaché ({@code salles.dept} n'étant écrit qu'à
   * la création).
   */
  private record CandidatSalleMatching(UUID id, String nomNorm, String deptEffectif) {}

  /**
   * Charge en une seule requête l'ensemble des salles utilisables comme candidats de rapprochement
   * par nom.
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
   * Équivalent en mémoire de {@code chercherSalleParTokens} ({@link ResolveurSalleParConcert}),
   * appliqué à des candidats déjà chargés — mêmes critères d'acceptation ({@link
   * CorrespondanceSalle#estDoublonSalle}, filtre dept, meilleur score Jaccard), sans requête SQL
   * par appel.
   *
   * @param nom nom brut de la salle à rapprocher
   * @param dept département connu du concert (nullable — désactive le filtre si absent)
   * @param candidats toutes les salles connues, préchargées
   * @return UUID de la meilleure salle candidate, ou {@code null} si aucune ne correspond
   */
  private UUID meilleurCandidatEnMemoire(
      String nom, String dept, List<CandidatSalleMatching> candidats) {
    String nomNorm = Normalisation.normaliserTexte(nom);
    UUID meilleurUuid = null;
    double meilleurScore = -1;
    for (CandidatSalleMatching candidat : candidats) {
      if (dept != null
          && candidat.deptEffectif() != null
          && !candidat.deptEffectif().equals(dept)) {
        continue;
      }
      if (!CorrespondanceSalle.estDoublonSalle(nomNorm, candidat.nomNorm())) continue;
      double score = CorrespondanceSalle.jaccardTokens(nomNorm, candidat.nomNorm());
      if (score > meilleurScore) {
        meilleurScore = score;
        meilleurUuid = candidat.id();
      }
    }
    return meilleurUuid;
  }
}
