/*
 * Strapontin — concert finder
 * Copyright (C) 2025 Kwaite
 */
package fr.strapontin.web.utilisateur;

import com.fasterxml.jackson.databind.ObjectMapper;
import fr.strapontin.web.securite.UtilisateurDetails;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/// Endpoint d'export des préférences utilisateur en JSON téléchargeable.
///
/// @since 2.0.0
/// @author Kwaite
@RestController
@RequestMapping("/api/preferences")
public class PreferencesController {

  private final NamedParameterJdbcTemplate jdbc;
  private final ObjectMapper objectMapper;

  public PreferencesController(NamedParameterJdbcTemplate jdbc, ObjectMapper objectMapper) {
    this.jdbc = jdbc;
    this.objectMapper = objectMapper;
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

  /// Importe les préférences depuis un fichier JSON exporté par Strapontin.
  ///
  /// <p>Mode {@code fusionner} : union des artistes, salles et départements existants.
  /// Mode {@code ecraser} : remplacement intégral des artistes et salles ;
  /// la config n'est remplacée que si {@code importerConfig} est vrai.
  /// Toutes les opérations DB s'exécutent dans une transaction unique.
  ///
  /// @param fichier        le fichier JSON (multipart)
  /// @param mode           {@code fusionner} ou {@code ecraser}
  /// @param importerConfig remplacer la config utilisateur si vrai
  /// @param auth           contexte d'authentification
  /// @return 200 si importé, 400 si format invalide
  @Transactional
  @PostMapping(value = "/importer", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public ResponseEntity<Map<String, Object>> importer(
      @RequestParam("fichier") MultipartFile fichier,
      @RequestParam(defaultValue = "fusionner") String mode,
      @RequestParam(defaultValue = "false") boolean importerConfig,
      Authentication auth) {
    String userId = ((UtilisateurDetails) auth.getPrincipal()).id();

    ExportComplet export;
    try {
      export = objectMapper.readValue(fichier.getBytes(), ExportComplet.class);
    } catch (Exception e) {
      Map<String, Object> erreur = new HashMap<>();
      erreur.put("erreur", "Fichier invalide ou format non reconnu.");
      return ResponseEntity.badRequest().body(erreur);
    }

    boolean ecraser = "ecraser".equals(mode);

    if (ecraser) {
      jdbc.update("DELETE FROM user_artistes WHERE user_id = :userId", Map.of("userId", userId));
      jdbc.update("DELETE FROM user_salles WHERE user_id = :userId", Map.of("userId", userId));
    }

    if (export.artistes() != null) {
      for (ArtisteExport a : export.artistes()) {
        if (a.nom() == null || a.nom().isBlank()) continue;
        jdbc.update(
            """
            INSERT INTO user_artistes (user_id, artiste_normalise, artiste_affiche)
            VALUES (:userId, :normalise, :nom)
            ON CONFLICT (user_id, artiste_normalise) DO NOTHING
            """,
            Map.of("userId", userId, "normalise", a.normalise(), "nom", a.nom()));
      }
    }

    if (export.salles() != null) {
      for (SalleExport s : export.salles()) {
        if (s.salleId() == null || s.salleId().isBlank()) continue;
        jdbc.update(
            """
            INSERT INTO user_salles (user_id, salle_id, tm_venue_id)
            SELECT :userId, :salleId, :tmVenueId
            WHERE EXISTS (SELECT 1 FROM salles WHERE id = :salleId)
            ON CONFLICT (user_id, salle_id) DO NOTHING
            """,
            Map.of(
                "userId",
                userId,
                "salleId",
                s.salleId(),
                "tmVenueId",
                s.tmVenueId() != null ? s.tmVenueId() : ""));
      }
    }

    if (importerConfig && export.config() != null) {
      ConfigExport c = export.config();
      String[] depts =
          c.departements() != null ? c.departements().toArray(String[]::new) : new String[0];
      jdbc.update(
          """
          INSERT INTO user_config
              (user_id, departements, filtre_disponibles, frequence_notifications,
               notif_artistes, notif_salles, theme, updated_at)
          VALUES (:userId, :depts, :filtreDisponibles, :frequence::frequence_notif,
                  :notifArtistes, :notifSalles, :theme::theme_interface, now())
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
              "userId",
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
    } else if (!ecraser && export.config() != null && export.config().departements() != null) {
      // Mode fusionner sans importerConfig : on fusionne uniquement les départements
      String[] nouveauxDepts = export.config().departements().toArray(String[]::new);
      jdbc.update(
          """
          UPDATE user_config
          SET departements = (
              SELECT ARRAY(
                  SELECT DISTINCT unnest(departements || :depts::text[])
                  ORDER BY 1
              )
          ),
          updated_at = now()
          WHERE user_id = :userId
          """,
          Map.of("userId", userId, "depts", nouveauxDepts));
    }

    return ResponseEntity.ok(Map.of("importe", true));
  }

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
