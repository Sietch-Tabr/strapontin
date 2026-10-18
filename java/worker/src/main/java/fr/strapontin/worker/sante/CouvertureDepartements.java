/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker.sante;

import fr.strapontin.sources.DepartementsConnus;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Couverture des départements : relève chaque nuit le nombre de concerts à
 * venir de chacun et signale ceux qui n'en ont plus aucun, tant que l'admin
 * n'a pas reconnu cette absence. Un département qui retrouve des concerts
 * referme son signalement et réarme le suivi.
 * @since 3.0.6
 * @author Kwaite
 */
@Service
public class CouvertureDepartements {

  /** Type du signalement publié pour un département sans concert. */
  public static final String TYPE_SIGNALEMENT = "departement_sans_concert";

  private static final String CLE_DEPT = "dept";

  private final NamedParameterJdbcTemplate jdbc;
  private final SignalementMatchingService signalements;

  /**
   * @param jdbc accès JDBC
   * @param signalements publication des départements sans concert
   */
  public CouvertureDepartements(
      final NamedParameterJdbcTemplate jdbc, final SignalementMatchingService signalements) {
    this.jdbc = jdbc;
    this.signalements = signalements;
  }

  /**
   * Relève la couverture et publie les signalements.
   * @return nombre de départements sans concert dont l'absence n'est pas reconnue
   */
  public int verifier() {
    final Map<String, Integer> comptes = compterConcertsAVenir();
    final Set<UUID> encoreSignales = new HashSet<>();
    for (final String dept : new TreeSet<>(DepartementsConnus.codes())) {
      final int nombre = comptes.getOrDefault(dept, 0);
      enregistrer(dept, nombre);
      if (nombre == 0 && !absenceReconnue(dept)) {
        final UUID entite = uuidDe(dept);
        signalements.signaler(
            TYPE_SIGNALEMENT,
            "departement",
            entite,
            "moyenne",
            Map.of("departement", dept, "sansConcertDepuis", sansConcertDepuis(dept)));
        encoreSignales.add(entite);
      }
    }
    signalements.clore(TYPE_SIGNALEMENT, encoreSignales);
    return encoreSignales.size();
  }

  private Map<String, Integer> compterConcertsAVenir() {
    final Map<String, Integer> comptes = new HashMap<>();
    jdbc.query(
        """
        SELECT dept, count(*) AS nombre FROM evenements
        WHERE date >= CURRENT_DATE AND dept IS NOT NULL
        GROUP BY dept
        """,
        Map.of(),
        rs -> {
          comptes.put(rs.getString(CLE_DEPT), rs.getInt("nombre"));
        });
    return comptes;
  }

  // Un département qui retrouve des concerts oublie la reconnaissance de son
  // absence passée.
  private void enregistrer(final String dept, final int nombre) {
    jdbc.update(
        """
        INSERT INTO couverture_departements (dept, nb_concerts, sans_concert_depuis)
        VALUES (:dept, :nombre, CASE WHEN :nombre = 0 THEN CURRENT_DATE END)
        ON CONFLICT (dept) DO UPDATE SET
          nb_concerts = :nombre,
          sans_concert_depuis = CASE WHEN :nombre > 0 THEN NULL
                                     ELSE COALESCE(couverture_departements.sans_concert_depuis,
                                                   CURRENT_DATE) END,
          couverture_connue_le = CASE WHEN :nombre > 0 THEN NULL
                                      ELSE couverture_departements.couverture_connue_le END,
          mis_a_jour_le = now()
        """,
        Map.of(CLE_DEPT, dept, "nombre", nombre));
  }

  private boolean absenceReconnue(final String dept) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT couverture_connue_le IS NOT NULL FROM couverture_departements"
                + " WHERE dept = :dept",
            Map.of(CLE_DEPT, dept),
            Boolean.class));
  }

  private String sansConcertDepuis(final String dept) {
    return jdbc.queryForObject(
        "SELECT sans_concert_depuis::text FROM couverture_departements WHERE dept = :dept",
        Map.of(CLE_DEPT, dept),
        String.class);
  }

  /**
   * @param dept code du département
   * @return identifiant stable de l'entité d'un signalement de département
   */
  /* package-private : partagé avec les tests (même package). */
  static UUID uuidDe(final String dept) {
    return UUID.nameUUIDFromBytes(("departement:" + dept).getBytes(StandardCharsets.UTF_8));
  }
}
