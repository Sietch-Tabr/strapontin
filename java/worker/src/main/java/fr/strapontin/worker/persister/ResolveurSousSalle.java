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
import java.util.function.BiFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * Salle interne d'un complexe connu (nom qualifié, « La Seine Musicale -
 * Grande Seine ») : résolue ou créée en sous-salle du complexe plutôt que
 * de mélanger ses concerts avec ceux du complexe.
 * @since 3.0.3
 * @author Kwaite
 */
final class ResolveurSousSalle {

  private static final Logger LOG = LoggerFactory.getLogger(ResolveurSousSalle.class);
  private static final String CLE_SALLE_CLE = "salleCle";

  private final NamedParameterJdbcTemplate jdbc;
  private final BiFunction<String, String, UUID> chercherParMots;

  /**
   * @param jdbc accès JDBC
   * @param chercherParMots recherche d'une salle par les mots de son nom et
   *     son département
   */
  /* package-private : instancié par ResolveurSalleParConcert (même package). */
  ResolveurSousSalle(
      final NamedParameterJdbcTemplate jdbc,
      final BiFunction<String, String, UUID> chercherParMots) {
    this.jdbc = jdbc;
    this.chercherParMots = chercherParMots;
  }

  /**
   * @param trouvee salle trouvée par les mots du nom, ou {@code null}
   * @param nom nom publié, éventuellement qualifié
   * @param dept département du concert
   * @return la sous-salle si la salle trouvée est le complexe sans le
   *     qualificatif du nom, sinon la salle trouvée
   */
  /* package-private : appelé par ResolveurSalleParConcert (même package). */
  UUID plutotQueLeComplexe(final UUID trouvee, final String nom, final String dept) {
    if (trouvee == null || !CorrespondanceSalle.qualificatifAbsentDe(nom, nomNormDe(trouvee))) {
      return trouvee;
    }
    final UUID sousSalle = resoudre(nom, dept);
    return sousSalle != null ? sousSalle : trouvee;
  }

  /**
   * @param nom nom brut entrant, potentiellement qualifié
   * @param dept département connu du concert
   * @return UUID de la sous-salle, ou {@code null} sans qualificatif ni
   *     complexe connu
   */
  /* package-private : appelé par ResolveurSalleParConcert (même package). */
  UUID resoudre(final String nom, final String dept) {
    final String base = CorrespondanceSalle.extraireBase(nom);
    if (base == null) {
      return null;
    }
    final UUID complexeId = chercherParMots.apply(base, dept);
    if (complexeId == null) {
      return null;
    }
    final String salleCle = Normalisation.normaliserTexte(nom);
    // MapSqlParameterSource : dept peut être null (Map.of le refuse).
    jdbc.update(
        "INSERT INTO salles (salle_cle, nom, nom_norm, dept, salle_parent_id)"
            + " VALUES (:salleCle, :nom, :nomNorm, :dept, CAST(:parentId AS uuid))"
            + " ON CONFLICT (salle_cle) DO NOTHING",
        new MapSqlParameterSource()
            .addValue(CLE_SALLE_CLE, salleCle)
            .addValue("nom", nom)
            .addValue("nomNorm", salleCle)
            .addValue("dept", dept)
            .addValue("parentId", complexeId.toString()));
    final UUID sousSalleId =
        jdbc.queryForObject(
            "SELECT id FROM salles WHERE salle_cle = :salleCle",
            Map.of(CLE_SALLE_CLE, salleCle),
            UUID.class);
    LOG.info(
        "sous-salle créée pour complexe nom={} base={} complexeId={} sousSalleId={}",
        nom,
        base,
        complexeId,
        sousSalleId);
    return sousSalleId;
  }

  private String nomNormDe(final UUID salleId) {
    final List<String> noms =
        jdbc.queryForList(
            "SELECT nom_norm FROM salles WHERE id = CAST(:id AS uuid)",
            Map.of("id", salleId.toString()),
            String.class);
    return noms.isEmpty() ? null : noms.get(0);
  }
}
