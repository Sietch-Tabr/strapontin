/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.utilisateur;

import com.fasterxml.jackson.databind.ObjectMapper;
import fr.strapontin.core.Normalisation;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/// Service d'import/export des préférences utilisateur.
///
/// <p>L'import est atomique ({@code @Transactional}) — un échec partiel annule toute l'opération.
///
/// @since 2.1.0
/// @author Kwaite
@Service
public class PreferencesService {

  private static final Logger log = LoggerFactory.getLogger(PreferencesService.class);
  private static final String CLE_USER_ID = "userId";
  private static final String WORKER_URL = "http://localhost:8081/trigger";

  private final NamedParameterJdbcTemplate jdbc;
  private final ObjectMapper objectMapper;
  private final HttpClient httpClient;

  /// @param jdbc         accès JDBC
  /// @param objectMapper désérialiseur JSON
  public PreferencesService(NamedParameterJdbcTemplate jdbc, ObjectMapper objectMapper) {
    this.jdbc = jdbc;
    this.objectMapper = objectMapper;
    this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
  }

  /// Désérialise et valide un fichier d'export.
  ///
  /// @param contenu  octets du fichier JSON
  /// @return l'export désérialisé, ou {@code null} si invalide
  public ExportComplet deserialiserExport(byte[] contenu) {
    try {
      return objectMapper.readValue(contenu, ExportComplet.class);
    } catch (Exception e) {
      log.warn("Import préférences : désérialisation échouée", e);
      return null;
    }
  }

  /// Importe les préférences dans une transaction atomique.
  ///
  /// @param userId        identifiant de l'utilisateur
  /// @param export        export désérialisé
  /// @param ecraser       {@code true} pour remplacer artistes et salles existants
  /// @param importerConfig {@code true} pour remplacer toute la config
  /// @return résultat de l'import (artistes et salles traités/rejetés)
  @Transactional
  public ResultatImportComplet importer(
      String userId, ExportComplet export, boolean ecraser, boolean importerConfig) {
    if (ecraser) {
      jdbc.update("DELETE FROM user_artistes WHERE user_id = :userId", Map.of(CLE_USER_ID, userId));
      jdbc.update("DELETE FROM user_salles WHERE user_id = :userId", Map.of(CLE_USER_ID, userId));
    }

    int[] artistes = {0, 0};
    int[] salles = {0, 0};

    if (export.artistes() != null) {
      int[] res = importerArtistes(userId, export.artistes());
      artistes = res;
    }
    if (export.salles() != null) {
      int[] res = importerSalles(userId, export.salles());
      salles = res;
    }

    if (importerConfig && export.config() != null) {
      importerConfig(userId, export.config());
    } else if (!ecraser && export.config() != null && export.config().departements() != null) {
      fusionnerDepartements(userId, export.config().departements().toArray(String[]::new));
    }

    declencherWorkerApresImport(export.salles());
    return new ResultatImportComplet(artistes[0], salles[0], artistes[1] + salles[1]);
  }

  /// Exporte les préférences de l'utilisateur (artistes, salles, config).
  ///
  /// @param userId identifiant de l'utilisateur
  /// @return l'export complet
  public ExportComplet exporter(String userId) {
    List<ArtisteExport> artistes =
        jdbc.query(
            """
            SELECT artiste_affiche, artiste_normalise FROM user_artistes
            WHERE user_id = :userId ORDER BY artiste_normalise
            """,
            Map.of(CLE_USER_ID, userId),
            (rs, _) ->
                new ArtisteExport(
                    rs.getString("artiste_affiche"), rs.getString("artiste_normalise")));

    List<SalleExport> salles =
        jdbc.query(
            """
            SELECT us.salle_id, us.tm_venue_id, s.nom
            FROM user_salles us
            JOIN salles s ON s.id = us.salle_id
            WHERE us.user_id = :userId ORDER BY us.salle_id
            """,
            Map.of(CLE_USER_ID, userId),
            (rs, _) ->
                new SalleExport(
                    rs.getString("salle_id"), rs.getString("tm_venue_id"), rs.getString("nom")));

    List<ConfigExport> configRows =
        jdbc.query(
            """
            SELECT departements, filtre_disponibles, frequence_notifications,
                   notif_artistes, notif_salles, theme
            FROM user_config WHERE user_id = :userId
            """,
            Map.of(CLE_USER_ID, userId),
            (rs, _) -> {
              String[] depts =
                  rs.getArray("departements") != null
                      ? (String[]) rs.getArray("departements").getArray()
                      : new String[0];
              return new ConfigExport(
                  Arrays.asList(depts),
                  rs.getBoolean("filtre_disponibles"),
                  rs.getString("frequence_notifications"),
                  rs.getBoolean("notif_artistes"),
                  rs.getBoolean("notif_salles"),
                  rs.getString("theme"));
            });

    return new ExportComplet(
        1,
        Instant.now().toString(),
        artistes,
        salles,
        configRows.isEmpty() ? null : configRows.get(0));
  }

  private int[] importerArtistes(String userId, List<ArtisteExport> artistes) {
    Integer nbExistants =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM user_artistes WHERE user_id = :userId",
            Map.of(CLE_USER_ID, userId),
            Integer.class);
    int restant = Math.max(0, 500 - (nbExistants != null ? nbExistants : 0));
    int traites = 0, rejetes = 0;
    for (ArtisteExport a : artistes) {
      if (a.nom() == null || a.nom().isBlank()) {
        rejetes++;
        continue;
      }
      if (traites >= restant) {
        rejetes++;
        continue;
      }
      String normalise = Normalisation.normaliserTexte(a.nom());
      jdbc.update(
          """
          INSERT INTO user_artistes (user_id, artiste_normalise, artiste_affiche)
          VALUES (:userId, :normalise, :nom)
          ON CONFLICT (user_id, artiste_normalise) DO NOTHING
          """,
          Map.of(CLE_USER_ID, userId, "normalise", normalise, "nom", a.nom().strip()));
      traites++;
    }
    return new int[] {traites, rejetes};
  }

  private int[] importerSalles(String userId, List<SalleExport> salles) {
    Integer nbExistants =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM user_salles WHERE user_id = :userId",
            Map.of(CLE_USER_ID, userId),
            Integer.class);
    int restant = Math.max(0, 200 - (nbExistants != null ? nbExistants : 0));
    int traites = 0, rejetes = 0;
    for (SalleExport s : salles) {
      String nomSalle = s.nom() != null && !s.nom().isBlank() ? s.nom().strip() : s.salleId();
      if (nomSalle == null || nomSalle.isBlank()) {
        rejetes++;
        continue;
      }
      String salleId = Normalisation.normaliserSalleCle(nomSalle);
      if (salleId.isEmpty()) {
        rejetes++;
        continue;
      }
      if (traites >= restant) {
        rejetes++;
        continue;
      }
      String tmVenueId = s.tmVenueId() != null && !s.tmVenueId().isBlank() ? s.tmVenueId() : null;
      String nomNorm = Normalisation.normaliserTexte(nomSalle);
      jdbc.update(
          """
          INSERT INTO salles (id, nom, nom_norm)
          VALUES (:salleId, :nom, :nomNorm)
          ON CONFLICT (id) DO UPDATE SET nom = EXCLUDED.nom, nom_norm = EXCLUDED.nom_norm
          """,
          Map.of("salleId", salleId, "nom", nomSalle, "nomNorm", nomNorm));
      Map<String, Object> paramsUs = new HashMap<>();
      paramsUs.put(CLE_USER_ID, userId);
      paramsUs.put("salleId", salleId);
      paramsUs.put("tmVenueId", tmVenueId);
      jdbc.update(
          """
          INSERT INTO user_salles (user_id, salle_id, tm_venue_id)
          VALUES (:userId, :salleId, :tmVenueId)
          ON CONFLICT (user_id, salle_id) DO NOTHING
          """,
          paramsUs);
      traites++;
    }
    return new int[] {traites, rejetes};
  }

  private void importerConfig(String userId, ConfigExport c) {
    String[] depts =
        c.departements() != null ? c.departements().toArray(String[]::new) : new String[0];
    jdbc.update(
        """
        INSERT INTO user_config
            (user_id, departements, filtre_disponibles, frequence_notifications,
             notif_artistes, notif_salles, theme, updated_at)
        VALUES (:userId, :depts, :filtreDisponibles, CAST(:frequence AS frequence_notif),
                :notifArtistes, :notifSalles, CAST(:theme AS theme_interface), now())
        ON CONFLICT (user_id) DO UPDATE SET
            departements            = EXCLUDED.departements,
            filtre_disponibles      = EXCLUDED.filtre_disponibles,
            frequence_notifications = EXCLUDED.frequence_notifications,
            notif_artistes          = EXCLUDED.notif_artistes,
            notif_salles            = EXCLUDED.notif_salles,
            theme                   = EXCLUDED.theme,
            updated_at              = now()
        """,
        Map.of(
            CLE_USER_ID,
            userId,
            "depts",
            depts,
            "filtreDisponibles",
            c.filtreDisponibles(),
            "frequence",
            c.frequenceNotifications() != null ? c.frequenceNotifications() : "jamais",
            "notifArtistes",
            c.notifArtistes(),
            "notifSalles",
            c.notifSalles(),
            "theme",
            c.theme() != null ? c.theme() : "auto"));
  }

  private void fusionnerDepartements(String userId, String[] nouveauxDepts) {
    jdbc.update(
        """
        UPDATE user_config
        SET departements = (
            SELECT ARRAY(
                SELECT DISTINCT unnest(departements || CAST(:depts AS text[]))
                ORDER BY 1
            )
        ),
        updated_at = now()
        WHERE user_id = :userId
        """,
        Map.of(CLE_USER_ID, userId, "depts", nouveauxDepts));
  }

  private static final java.util.regex.Pattern VENUE_ID_PATTERN =
      java.util.regex.Pattern.compile("[a-zA-Z0-9\\-]{1,64}");

  private void declencherWorkerApresImport(List<SalleExport> salles) {
    envoyerPostWorker(WORKER_URL + "/artiste");
    if (salles == null) return;
    salles.stream()
        .filter(s -> s.tmVenueId() != null && !s.tmVenueId().isBlank())
        .map(SalleExport::tmVenueId)
        .filter(venueId -> VENUE_ID_PATTERN.matcher(venueId).matches())
        .distinct()
        .forEach(venueId -> envoyerPostWorker(WORKER_URL + "/salle/" + venueId));
  }

  private void envoyerPostWorker(String url) {
    httpClient
        .sendAsync(
            HttpRequest.newBuilder()
                .uri(URI.create(url))
                .POST(HttpRequest.BodyPublishers.noBody())
                .timeout(Duration.ofSeconds(5))
                .build(),
            HttpResponse.BodyHandlers.discarding())
        .exceptionally(
            e -> {
              log.warn("Déclenchement worker impossible : {}", url, e);
              return null;
            });
  }

  /// Résultat d'un import de préférences.
  public record ResultatImportComplet(int artistes, int salles, int rejetes) {}

  /// Nom du fichier d'export pour le téléchargement.
  ///
  /// @return nom avec la date du jour
  public static String nomFichierExport() {
    return "strapontin-preferences-" + LocalDate.now() + ".json";
  }
}
