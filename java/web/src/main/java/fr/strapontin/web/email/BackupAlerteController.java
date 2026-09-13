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
 * Endpoint interne appelé par backup-local.sh pour signaler le statut du backup nocturne.
 *
 * <p>Protégé par un secret partagé : l'appelant doit présenter le header {@code Authorization:
 * Bearer <BACKUP_INTERNAL_SECRET>}. Si la variable {@code BACKUP_INTERNAL_SECRET} est absente ou
 * que le token ne correspond pas, l'accès est refusé (403). Enregistre le statut dans {@code
 * logs_admin} et envoie un email d'alerte sur échec.
 *
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
   * @param backupSecret secret partagé attendu dans l'en-tête {@code Authorization}
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
   * Notifie l'app du statut du backup nocturne.
   *
   * <p>Sur {@code statut=echec} : log warn dans {@code logs_admin} + email admin. Sur {@code
   * statut=succes} : log info dans {@code logs_admin}.
   *
   * @param statut {@code succes} ou {@code echec}
   * @param horodatage l'horodatage du backup (format YYYYMMDD_HHMMSS)
   * @param raison la commande ayant échoué (optionnel, échec uniquement)
   * @param archive le chemin de l'archive créée (optionnel, succès uniquement)
   * @param authorization header {@code Authorization: Bearer <BACKUP_INTERNAL_SECRET>}
   * @return 200 si traité, 403 si le token est absent ou invalide
   */
  // Paramètres individuels imposés par le contrat @RequestParam (liaison HTTP), pas par choix de
  // conception — hors de la convention DTO du projet, réservée aux endpoints @RequestBody.
  @SuppressWarnings("PMD.UseObjectForClearerAPI")
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
