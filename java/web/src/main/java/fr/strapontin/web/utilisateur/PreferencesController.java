/*
 * Strapontin — concert finder
 * Copyright (C) 2025 Kwaite
 */
package fr.strapontin.web.utilisateur;

import fr.strapontin.web.securite.UtilisateurDetails;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/// Endpoint d'export des préférences utilisateur en JSON téléchargeable.
///
/// @since 2.0.0
/// @author Kwaite
@RestController
@RequestMapping("/api/preferences")
public class PreferencesController {

  private final NamedParameterJdbcTemplate jdbc;

  public PreferencesController(NamedParameterJdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  record ArtisteExport(String nom, String normalise) {}

  record SalleExport(String salleId, String tmVenueId) {}

  record ConfigExport(
      List<String> departements,
      boolean filtreDisponibles,
      String frequenceNotifications,
      boolean notifArtistes,
      boolean notifSalles,
      String theme) {}

  record ExportComplet(
      int version,
      String exporteAt,
      List<ArtisteExport> artistes,
      List<SalleExport> salles,
      ConfigExport config) {}

  /// Exporte les préférences de l'utilisateur courant (artistes, salles, config) en JSON.
  ///
  /// @param auth contexte d'authentification
  /// @return JSON téléchargeable avec les préférences
  @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<ExportComplet> exporter(Authentication auth) {
    String userId = ((UtilisateurDetails) auth.getPrincipal()).id();

    List<ArtisteExport> artistes =
        jdbc.query(
            """
            SELECT artiste_affiche, artiste_normalise FROM user_artistes
            WHERE user_id = :userId ORDER BY artiste_normalise
            """,
            Map.of("userId", userId),
            (rs, _) ->
                new ArtisteExport(
                    rs.getString("artiste_affiche"), rs.getString("artiste_normalise")));

    List<SalleExport> salles =
        jdbc.query(
            """
            SELECT salle_id, tm_venue_id FROM user_salles
            WHERE user_id = :userId ORDER BY salle_id
            """,
            Map.of("userId", userId),
            (rs, _) -> new SalleExport(rs.getString("salle_id"), rs.getString("tm_venue_id")));

    List<ConfigExport> configRows =
        jdbc.query(
            """
            SELECT departements, filtre_disponibles, frequence_notifications,
                   notif_artistes, notif_salles, theme
            FROM user_config WHERE user_id = :userId
            """,
            Map.of("userId", userId),
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

    ConfigExport config = configRows.isEmpty() ? null : configRows.get(0);
    String nomFichier = "strapontin-preferences-" + LocalDate.now() + ".json";

    return ResponseEntity.ok()
        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + nomFichier + "\"")
        .contentType(MediaType.APPLICATION_JSON)
        .body(new ExportComplet(1, Instant.now().toString(), artistes, salles, config));
  }
}
