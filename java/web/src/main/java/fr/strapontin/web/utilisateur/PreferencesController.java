/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.utilisateur;

import fr.strapontin.web.securite.UtilisateurDetails;
import java.util.HashMap;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/// Endpoint d'import/export des préférences utilisateur en JSON.
///
/// <p>HTTP pur — délègue toute la logique à {@link PreferencesService}.
///
/// @since 2.0.0
/// @author Kwaite
@RestController
@RequestMapping("/api/preferences")
public class PreferencesController {

  private final PreferencesService preferencesService;

  /// @param preferencesService service d'import/export des préférences
  public PreferencesController(PreferencesService preferencesService) {
    this.preferencesService = preferencesService;
  }

  /// Importe les préférences depuis un fichier JSON exporté par Strapontin.
  ///
  /// @param fichier        le fichier JSON (multipart)
  /// @param mode           {@code fusionner} ou {@code ecraser}
  /// @param importerConfig remplacer la config utilisateur si vrai
  /// @param auth           contexte d'authentification
  /// @return 200 avec stats si importé, 400 si format invalide, 422 si version inconnue
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
      return erreur(400, "Le fichier doit être au format JSON.");
    }

    ExportComplet export;
    try {
      export = preferencesService.deserialiserExport(fichier.getBytes());
    } catch (Exception e) {
      return erreur(400, "Le fichier doit être au format JSON.");
    }

    if (export == null) return erreur(400, "Fichier invalide ou format non reconnu.");
    if (export.version() != 1) {
      return erreur(422, "Version de fichier non supportée : " + export.version() + ".");
    }

    PreferencesService.ResultatImportComplet res =
        preferencesService.importer(ud.id(), export, "ecraser".equals(mode), importerConfig);

    Map<String, Object> corps = new HashMap<>();
    corps.put("importe", true);
    corps.put("artistes", res.artistes());
    corps.put("salles", res.salles());
    corps.put("rejetes", res.rejetes());
    return ResponseEntity.ok(corps);
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
    ExportComplet export = preferencesService.exporter(ud.id());
    return ResponseEntity.ok()
        .header(
            HttpHeaders.CONTENT_DISPOSITION,
            "attachment; filename=\"" + PreferencesService.nomFichierExport() + "\"")
        .contentType(MediaType.APPLICATION_JSON)
        .body(export);
  }

  private static ResponseEntity<Map<String, Object>> erreur(int status, String message) {
    Map<String, Object> corps = new HashMap<>();
    corps.put("erreur", message);
    return ResponseEntity.status(status).body(corps);
  }
}
