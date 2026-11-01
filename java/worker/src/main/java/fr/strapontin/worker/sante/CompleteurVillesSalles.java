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
 * Tient les villes des salles et des événements : une orthographe par commune,
 * ville renseignée depuis les concerts puis les lieux sources, département
 * déduit du code postal, ville alignée sur la commune de la position. Une
 * ville inconnue est signalée.
 * @since 3.0.6
 * @author Kwaite
 */
@Service
public class CompleteurVillesSalles {

  private static final Logger LOG = LoggerFactory.getLogger(CompleteurVillesSalles.class);
  private static final int NB_NOMS_SIGNALES = 10;
  private static final String CLE_VILLE = "ville";
  private static final String CLE_BRUTE = "brute";
  private static final int TAILLE_MIN_COMMUNE = 4;

  private final NamedParameterJdbcTemplate jdbc;
  private final LogsAdminWorker logsAdminWorker;
  private final AlignementCommuneSalles alignementCommune;

  /**
   * @param jdbc accès JDBC
   * @param logsAdminWorker signalement des salles dont la ville reste inconnue
   */
  public CompleteurVillesSalles(
      final NamedParameterJdbcTemplate jdbc,
      final LogsAdminWorker logsAdminWorker,
      final AlignementCommuneSalles alignementCommune) {
    this.jdbc = jdbc;
    this.logsAdminWorker = logsAdminWorker;
    this.alignementCommune = alignementCommune;
  }

  /**
   * Exécute les étapes dans l'ordre : orthographe, ville depuis les concerts,
   * ville depuis les lieux sources, département depuis le code postal, puis
   * département des fiches sans département depuis leur salle.
   * @return nombre de valeurs corrigées ou renseignées
   */
  public int completer() {
    final int normalisees = normaliserOrthographes();
    final int depuisConcerts = renseignerVilleDepuisConcerts();
    final int depuisLieux = renseignerVilleDepuisLieuxSources();
    final int depuisNoms = renseignerVilleDepuisNomDeLaSalle();
    final int departements = renseignerDepartementDepuisCodePostal();
    final int departementsFiches = renseignerDepartementDesFiches();
    final int communes = alignementCommune.aligner();
    signalerSallesSansVille();
    final int total =
        normalisees
            + depuisConcerts
            + depuisLieux
            + depuisNoms
            + departements
            + departementsFiches
            + communes;
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

  // « Casino du Gosier » où « Le Gosier » est déjà connue du département : la
  // commune la plus longue contenue dans le nom, si elle est unique.
  private int renseignerVilleDepuisNomDeLaSalle() {
    final Map<String, Map<String, String>> communesParDept = new HashMap<>();
    jdbc.query(
        "SELECT DISTINCT dept, ville FROM salles WHERE dept IS NOT NULL AND ville IS NOT NULL",
        rs -> {
          final String coeur = sansArticle(Normalisation.normaliserTexte(rs.getString(CLE_VILLE)));
          if (coeur.length() >= TAILLE_MIN_COMMUNE) {
            communesParDept
                .computeIfAbsent(rs.getString("dept"), _ -> new HashMap<>())
                .put(coeur, rs.getString(CLE_VILLE));
          }
        });
    int renseignees = 0;
    final List<Map<String, Object>> salles =
        jdbc.queryForList(
            "SELECT id, nom_norm, dept FROM salles WHERE ville IS NULL AND dept IS NOT NULL",
            Map.of());
    for (final Map<String, Object> salle : salles) {
      final String ville =
          communeContenue(
              " " + salle.get("nom_norm") + " ",
              communesParDept.getOrDefault((String) salle.get("dept"), Map.of()));
      if (ville != null) {
        renseignees +=
            jdbc.update(
                "UPDATE salles SET ville = :ville WHERE id = CAST(:id AS uuid) AND ville IS NULL",
                Map.of(CLE_VILLE, ville, "id", salle.get("id").toString()));
      }
    }
    return renseignees;
  }

  private static String communeContenue(final String nom, final Map<String, String> communes) {
    int tailleMax = 0;
    String retenue = null;
    boolean ambigue = false;
    for (final Map.Entry<String, String> commune : communes.entrySet()) {
      final int taille = commune.getKey().length();
      if (nom.contains(" " + commune.getKey() + " ") && taille >= tailleMax) {
        ambigue = taille == tailleMax && !commune.getValue().equals(retenue);
        tailleMax = taille;
        retenue = commune.getValue();
      }
    }
    return ambigue ? null : retenue;
  }

  private static String sansArticle(final String ville) {
    return ville.replaceFirst("^(le|la|les|l) ", "");
  }

  // Un produit sans code postal laisse la fiche sans département : elle
  // échappe aux fetchs et contrôles par département alors que sa salle en a un.
  private int renseignerDepartementDesFiches() {
    return jdbc.update(
        "UPDATE evenements e SET dept = s.dept FROM salles s"
            + " WHERE e.salle_id = s.id AND e.dept IS NULL AND s.dept IS NOT NULL",
        Map.of());
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
