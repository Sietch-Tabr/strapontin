/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker.sante;

import fr.strapontin.core.NomVille;
import fr.strapontin.core.Normalisation;
import fr.strapontin.worker.LogsAdminWorker;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Tient les villes des salles et des événements : une seule orthographe par commune, la ville de
 * chaque salle renseignée depuis ses concerts puis ses lieux sources, un département déduit du
 * code postal. Une salle dont la ville reste inconnue est signalée dans le journal admin.
 * @since 3.0.6
 * @author Kwaite
 */
@Service
public class CompleteurVillesSalles {

  private static final Logger LOG = LoggerFactory.getLogger(CompleteurVillesSalles.class);
  private static final int NB_NOMS_SIGNALES = 10;
  private static final String CLE_VILLE = "ville";
  private static final String CLE_BRUTE = "brute";

  private final NamedParameterJdbcTemplate jdbc;
  private final LogsAdminWorker logsAdminWorker;

  /**
   * @param jdbc accès JDBC
   * @param logsAdminWorker signalement des salles dont la ville reste inconnue
   */
  public CompleteurVillesSalles(
      final NamedParameterJdbcTemplate jdbc, final LogsAdminWorker logsAdminWorker) {
    this.jdbc = jdbc;
    this.logsAdminWorker = logsAdminWorker;
  }

  /**
   * Exécute les quatre étapes dans l'ordre : orthographe, ville depuis les concerts, ville depuis
   * les lieux sources, département depuis le code postal.
   * @return nombre de valeurs corrigées ou renseignées
   */
  public int completer() {
    final int normalisees = normaliserOrthographes();
    final int depuisConcerts = renseignerVilleDepuisConcerts();
    final int depuisLieux = renseignerVilleDepuisLieuxSources();
    final int departements = renseignerDepartementDepuisCodePostal();
    signalerSallesSansVille();
    final int total = normalisees + depuisConcerts + depuisLieux + departements;
    if (total > 0) {
      LOG.info(
          "villes des salles : {} orthographes, {} depuis concerts, {} depuis lieux sources,"
              + " {} départements",
          normalisees,
          depuisConcerts,
          depuisLieux,
          departements);
    }
    return total;
  }

  private int normaliserOrthographes() {
    int modifiees = 0;
    for (final String table : List.of("evenements", "salles")) {
      final List<String> villes =
          jdbc.queryForList(
              "SELECT DISTINCT ville FROM " + table + " WHERE ville IS NOT NULL",
              Map.of(),
              String.class);
      for (final String brute : villes) {
        final String propre = NomVille.nettoyer(brute);
        if (propre != null && !propre.equals(brute)) {
          modifiees +=
              jdbc.update(
                  "UPDATE " + table + " SET ville = :ville WHERE ville = :brute",
                  Map.of(CLE_VILLE, propre, CLE_BRUTE, brute));
        }
      }
    }
    return modifiees;
  }

  private int renseignerVilleDepuisConcerts() {
    return jdbc.update(
        """
        UPDATE salles s SET ville = m.ville
        FROM (
          SELECT DISTINCT ON (salle_id) salle_id, ville
          FROM (
            SELECT salle_id, ville, count(*) AS nb
            FROM evenements
            WHERE salle_id IS NOT NULL AND ville IS NOT NULL
            GROUP BY salle_id, ville
          ) x
          ORDER BY salle_id, nb DESC, ville
        ) m
        WHERE s.id = m.salle_id AND s.ville IS NULL
        """,
        Map.of());
  }

  private int renseignerVilleDepuisLieuxSources() {
    final Map<UUID, Map<String, Integer>> parSalle = new HashMap<>();
    jdbc.query(
        """
        SELECT s.id, ss.ville_source
        FROM salles s JOIN salle_sources ss ON ss.salle_id = s.id
        WHERE s.ville IS NULL AND ss.ville_source IS NOT NULL
        """,
        rs -> {
          final String propre = NomVille.nettoyer(rs.getString("ville_source"));
          if (propre != null) {
            parSalle
                .computeIfAbsent(rs.getObject("id", UUID.class), _ -> new TreeMap<>())
                .merge(propre, 1, Integer::sum);
          }
        });
    int renseignees = 0;
    for (final Map.Entry<UUID, Map<String, Integer>> salle : parSalle.entrySet()) {
      final String ville =
          salle.getValue().entrySet().stream()
              .max(Map.Entry.<String, Integer>comparingByValue())
              .orElseThrow()
              .getKey();
      renseignees +=
          jdbc.update(
              "UPDATE salles SET ville = :ville WHERE id = CAST(:id AS uuid) AND ville IS NULL",
              Map.of(CLE_VILLE, ville, "id", salle.getKey().toString()));
    }
    return renseignees;
  }

  private int renseignerDepartementDepuisCodePostal() {
    int renseignes = 0;
    final List<Map<String, Object>> salles =
        jdbc.queryForList(
            "SELECT id, code_postal FROM salles WHERE dept IS NULL AND code_postal IS NOT NULL",
            Map.of());
    for (final Map<String, Object> salle : salles) {
      final String dept = Normalisation.extraireDeptFrancais((String) salle.get("code_postal"));
      if (dept != null) {
        renseignes +=
            jdbc.update(
                "UPDATE salles SET dept = :dept WHERE id = CAST(:id AS uuid) AND dept IS NULL",
                Map.of("dept", dept, "id", salle.get("id").toString()));
      }
    }
    return renseignes;
  }

  private void signalerSallesSansVille() {
    final List<String> noms =
        jdbc.queryForList(
            "SELECT nom FROM salles WHERE ville IS NULL ORDER BY nom", Map.of(), String.class);
    if (noms.isEmpty()) {
      return;
    }
    logsAdminWorker.inserer(
        "warn",
        "villes-salles",
        noms.size()
            + " salle(s) sans ville, déterminable ni par leurs concerts ni par leurs lieux"
            + " sources",
        Map.of(
            "nombre",
            noms.size(),
            "exemples",
            List.copyOf(noms.subList(0, Math.min(NB_NOMS_SIGNALES, noms.size())))));
  }
}
