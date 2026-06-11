/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.email;

import fr.strapontin.web.admin.LogAdminService;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/// Endpoint interne appelé par backup-local.sh pour signaler le statut du backup nocturne.
///
/// <p>Protégé par un secret partagé : l'appelant doit présenter le header
/// {@code Authorization: Bearer <BACKUP_INTERNAL_SECRET>}. Si la variable
/// {@code BACKUP_INTERNAL_SECRET} est absente ou que le token ne correspond pas, l'accès est refusé
/// (403). Enregistre le statut dans {@code logs_admin} et envoie un email d'alerte sur échec.
///
/// @since 2.1.1
/// @author Kwaite
@RestController
public class BackupAlerteController {

  private final EmailService emailService;
  private final LogAdminService logAdminService;
  private final String backupSecret;

  public BackupAlerteController(
      EmailService emailService,
      LogAdminService logAdminService,
      @Value("${BACKUP_INTERNAL_SECRET:}") String backupSecret) {
    this.emailService = emailService;
    this.logAdminService = logAdminService;
    this.backupSecret = backupSecret;
  }

  /// Notifie l'app du statut du backup nocturne.
  ///
  /// <p>Sur {@code statut=echec} : log warn dans {@code logs_admin} + email admin.
  /// Sur {@code statut=succes} : log info dans {@code logs_admin}.
  ///
  /// @param statut        {@code succes} ou {@code echec}
  /// @param horodatage    l'horodatage du backup (format YYYYMMDD_HHMMSS)
  /// @param raison        la commande ayant échoué (optionnel, échec uniquement)
  /// @param archive       le chemin de l'archive créée (optionnel, succès uniquement)
  /// @param authorization header {@code Authorization: Bearer <BACKUP_INTERNAL_SECRET>}
  /// @return 200 si traité, 403 si le token est absent ou invalide
  @PostMapping("/api/interne/backup-statut")
  public ResponseEntity<Map<String, Object>> signalerStatut(
      @RequestParam(defaultValue = "") String statut,
      @RequestParam(defaultValue = "") String horodatage,
      @RequestParam(defaultValue = "") String raison,
      @RequestParam(defaultValue = "") String archive,
      @RequestHeader(value = "Authorization", defaultValue = "") String authorization) {
    if (backupSecret.isBlank() || !("Bearer " + backupSecret).equals(authorization)) {
      return ResponseEntity.status(403).body(Map.of("erreur", "Accès refusé."));
    }
    if ("succes".equals(statut)) {
      logAdminService.logAdmin(
          "info",
          "web",
          "backup local terminé",
          Map.of("horodatage", horodatage, "archive", archive));
    } else {
      logAdminService.logAdmin(
          "warn", "web", "backup local échoué", Map.of("horodatage", horodatage, "raison", raison));
      emailService.envoyerAlerteBackup(horodatage, raison);
    }
    return ResponseEntity.ok(Map.of("ok", true));
  }
}
