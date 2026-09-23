/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.email;

import fr.strapontin.web.admin.LogAdminService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Endpoint interne appelé par backup-local.sh pour signaler
 * le statut du backup nocturne (détail : CLAUDE.md, Sécurité
 * admin/sessions).
 * @since 2.1.1
 * @author Kwaite
 */
@RestController
public class BackupAlerteController {

  private static final String STATUT_SUCCES = "succes";

  private final EmailAlerteService emailAlerteService;
  private final LogAdminService logAdminService;
  private final String backupSecret;

  /**
   * @param emailAlerteService résolution du destinataire d'alerte
   * @param logAdminService journalisation du statut reçu
   * @param backupSecret secret partagé attendu dans l'en-tête
   *     {@code Authorization}
   */
  public BackupAlerteController(
      final EmailAlerteService emailAlerteService,
      final LogAdminService logAdminService,
      @Value("${BACKUP_INTERNAL_SECRET:}") final String backupSecret) {
    this.emailAlerteService = emailAlerteService;
    this.logAdminService = logAdminService;
    this.backupSecret = backupSecret;
  }

  /**
   * Notifie l'app du statut du backup nocturne, paramètres
   * auto-descriptifs (détail : CLAUDE.md, Sécurité admin/sessions).
   * @return 200 si traité, 403 si le token est absent/invalide
   */
  @PostMapping("/api/interne/backup-statut")
  public ResponseEntity<Map<String, Object>> signalerStatut(
      @RequestParam(defaultValue = "") final String statut,
      @RequestParam(defaultValue = "") final String horodatage,
      @RequestParam(defaultValue = "") final String raison,
      @RequestParam(defaultValue = "") final String archive,
      @RequestHeader(value = "Authorization", defaultValue = "") final String authorization) {
    final String attendu = "Bearer " + backupSecret;
    if (backupSecret.isBlank()
        || !MessageDigest.isEqual(
            attendu.getBytes(StandardCharsets.UTF_8),
            authorization.getBytes(StandardCharsets.UTF_8))) {
      return ResponseEntity.status(403).body(Map.of("erreur", "Accès refusé."));
    }
    if (STATUT_SUCCES.equals(statut)) {
      logAdminService.logAdmin(
          "info",
          "web",
          "backup local terminé",
          Map.of("horodatage", horodatage, "archive", archive));
    } else {
      logAdminService.logAdmin(
          "warn", "web", "backup local échoué", Map.of("horodatage", horodatage, "raison", raison));
      emailAlerteService.envoyerAlerteBackup(horodatage, raison);
    }
    return ResponseEntity.ok(Map.of("ok", true));
  }
}
