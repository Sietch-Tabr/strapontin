/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.email;

import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/// Endpoint interne appelé par backup-local.sh en cas d'échec du backup nocturne.
///
/// <p>Protégé par un token partagé ({@code BACKUP_ALERTE_TOKEN} dans {@code .env}).
/// Accessible sans authentification session — le token fait office de secret.
///
/// @since 2.1.1
/// @author Kwaite
@RestController
public class BackupAlerteController {

  @Value("${BACKUP_ALERTE_TOKEN:}")
  private String tokenAttendu;

  private final EmailService emailService;

  public BackupAlerteController(EmailService emailService) {
    this.emailService = emailService;
  }

  /// Notifie l'admin par email qu'un backup nocturne a échoué.
  ///
  /// @param token       le token de sécurité (header {@code X-Backup-Token})
  /// @param horodatage  l'horodatage du backup échoué (optionnel)
  /// @return 200 si l'email est envoyé, 401 si le token est invalide, 503 si non configuré
  @PostMapping("/api/interne/backup-alerte")
  public ResponseEntity<Map<String, Object>> alerterBackup(
      @RequestHeader(value = "X-Backup-Token", defaultValue = "") String token,
      @RequestParam(defaultValue = "") String horodatage) {
    if (tokenAttendu.isBlank()) {
      return ResponseEntity.status(503).body(Map.of("erreur", "Token non configuré."));
    }
    if (!tokenAttendu.equals(token)) {
      return ResponseEntity.status(401).body(Map.of("erreur", "Token invalide."));
    }
    emailService.envoyerAlerteBackup(horodatage);
    return ResponseEntity.ok(Map.of("ok", true));
  }
}
