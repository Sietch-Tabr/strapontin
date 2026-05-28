/*
 * Strapontin — concert finder
 * Copyright (C) 2025 Kwaite
 */
package fr.strapontin.web.utilisateur;

import com.fasterxml.jackson.databind.ObjectMapper;
import fr.strapontin.core.Normalisation;
import fr.strapontin.web.securite.UtilisateurDetails;
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
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
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

/// Endpoint d'import/export des préférences utilisateur en JSON téléchargeable.
///
/// @since 2.0.0
/// @author Kwaite
@RestController
@RequestMapping("/api/preferences")
public class PreferencesController {

  private static final Logger log = LoggerFactory.getLogger(PreferencesController.class);
  private static final String CLE_USER_ID = "userId";
  private static final String WORKER_URL = "http://localhost:8081/trigger";

  private final NamedParameterJdbcTemplate jdbc;
  private final ObjectMapper objectMapper;
  private final HttpClient httpClient;

  public PreferencesController(NamedParameterJdbcTemplate jdbc, ObjectMapper objectMapper) {
    this.jdbc = jdbc;
    this.objectMapper = objectMapper;
    this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
  }

  record ArtisteExport(String nom, String normalise) {}

  record SalleExport(String salleId, String tmVenueId, String nom) {}

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

  private record ResultatImport(int traites, int rejetes) {}

  /// Importe les préférences depuis un fichier JSON exporté par Strapontin.
  ///
  /// Mode `fusionner` : union des artistes, salles et départements existants.
  /// Mode `ecraser` : remplacement intégral des artistes et salles ;
  /// la config n'est remplacée que si `importerConfig` est vrai.
  /// Toutes les opérations DB s'exécutent dans une transaction unique.
  ///
  /// @param fichier        le fichier JSON (multipart)
  /// @param mode           `fusionner` ou `ecraser`
  /// @param importerConfig remplacer la config utilisateur si vrai
  /// @param auth           contexte d'authentification
  /// @return 200 avec stats si importé, 400 si format invalide, 422 si version inconnue
  @Transactional
  @PostMapping(value = "/importer", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public ResponseEntity<Map<String, Object>> importer(
      @RequestParam MultipartFile fichier,
      @RequestParam(defaultValue = "fusionner") String mode,
      @RequestParam(defaultValue = "false") boolean importerConfig,
      Authentication auth) {
    if (!(auth.getPrincipal() instanceof UtilisateurDetails ud)) {
      throw new IllegalStateException("Principal inattendu");
    }

    String contentType = fichier.getContentType();
    if (contentType == null || !contentType.equals(MediaType.APPLICATION_JSON_VALUE)) {
      Map<String, Object> erreur = new HashMap<>();
      erreur.put("erreur", "Le fichier doit être au format JSON.");
      return ResponseEntity.badRequest().body(erreur);
    }

    ExportComplet export;
    try {
      export = objectMapper.readValue(fichier.getBytes(), ExportComplet.class);
    } catch (Exception e) {
      log.warn("Import préférences : désérialisation échouée", e);
      Map<String, Object> erreur = new HashMap<>();
      erreur.put("erreur", "Fichier invalide ou format non reconnu.");
      return ResponseEntity.badRequest().body(erreur);
    }

    if (export.version() != 1) {
      Map<String, Object> erreur = new HashMap<>();
      erreur.put("erreur", "Version de fichier non supportée : " + export.version() + ".");
      return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(erreur);
    }

    String userId = ud.id();
    boolean ecraser = "ecraser".equals(mode);
    if (ecraser) {
      jdbc.update("DELETE FROM user_artistes WHERE user_id = :userId", Map.of(CLE_USER_ID, userId));
      jdbc.update("DELETE FROM user_salles WHERE user_id = :userId", Map.of(CLE_USER_ID, userId));
    }

    ResultatImport resArtistes = new ResultatImport(0, 0);
    ResultatImport resSalles = new ResultatImport(0, 0);

    if (export.artistes() != null) resArtistes = importerArtistes(userId, export.artistes());
    if (export.salles() != null) resSalles = importerSalles(userId, export.salles());

    if (importerConfig && export.config() != null) {
      importerConfig(userId, export.config());
    } else if (!ecraser && export.config() != null && export.config().departements() != null) {
      fusionnerDepartements(userId, export.config().departements().toArray(String[]::new));
    }

    declencherWorkerApresImport(export.salles());

    Map<String, Object> corps = new HashMap<>();
    corps.put("importe", true);
    corps.put("artistes", resArtistes.traites());
    corps.put("salles", resSalles.traites());
    corps.put("rejetes", resArtistes.rejetes() + resSalles.rejetes());
    return ResponseEntity.ok(corps);
  }

  private ResultatImport importerArtistes(String userId, List<ArtisteExport> artistes) {
    Integer nbExistants =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM user_artistes WHERE user_id = :userId",
            Map.of(CLE_USER_ID, userId),
            Integer.class);
    int restant = Math.max(0, 500 - (nbExistants != null ? nbExistants : 0));
    int traites = 0;
    int rejetes = 0;
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
    return new ResultatImport(traites, rejetes);
  }

  private ResultatImport importerSalles(String userId, List<SalleExport> salles) {
    Integer nbExistants =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM user_salles WHERE user_id = :userId",
            Map.of(CLE_USER_ID, userId),
            Integer.class);
    int restant = Math.max(0, 200 - (nbExistants != null ? nbExistants : 0));
    int traites = 0;
    int rejetes = 0;
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
    return new ResultatImport(traites, rejetes);
  }

  private void declencherWorkerApresImport(List<SalleExport> salles) {
    envoyerPostWorker(WORKER_URL + "/artiste");
    if (salles == null) return;
    salles.stream()
        .filter(s -> s.tmVenueId() != null && !s.tmVenueId().isBlank())
        .map(SalleExport::tmVenueId)
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

  /// Exporte les préférences de l'utilisateur courant (artistes, salles, config) en JSON.
  ///
  /// @param auth contexte d'authentification
  /// @return JSON téléchargeable avec les préférences
  @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<ExportComplet> exporter(Authentication auth) {
    if (!(auth.getPrincipal() instanceof UtilisateurDetails ud)) {
      throw new IllegalStateException("Principal inattendu");
    }
    String userId = ud.id();

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

    ConfigExport config = configRows.isEmpty() ? null : configRows.get(0);
    String nomFichier = "strapontin-preferences-" + LocalDate.now() + ".json";

    return ResponseEntity.ok()
        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + nomFichier + "\"")
        .contentType(MediaType.APPLICATION_JSON)
        .body(new ExportComplet(1, Instant.now().toString(), artistes, salles, config));
  }
}
