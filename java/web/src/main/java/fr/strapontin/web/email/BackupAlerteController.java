/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.email;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/// Endpoint interne appelé par backup-local.sh en cas d'échec du backup nocturne.
///
/// <p>Accessible uniquement depuis localhost (127.0.0.1) — pas de token requis.
///
/// @since 2.1.1
/// @author Kwaite
@RestController
public class BackupAlerteController {

  private final EmailService emailService;

  public BackupAlerteController(EmailService emailService) {
    this.emailService = emailService;
  }

  /// Notifie l'admin par email qu'un backup nocturne a échoué.
  ///
  /// @param horodatage l'horodatage du backup échoué (optionnel)
  /// @param request    la requête HTTP (pour vérification de l'IP source)
  /// @return 200 si l'email est envoyé, 403 si l'appel ne vient pas de localhost
  @PostMapping("/api/interne/backup-alerte")
  public ResponseEntity<Map<String, Object>> alerterBackup(
      @RequestParam(defaultValue = "") String horodatage, HttpServletRequest request) {
    if (!"127.0.0.1".equals(request.getRemoteAddr())
        && !"0:0:0:0:0:0:0:1".equals(request.getRemoteAddr())) {
      return ResponseEntity.status(403).body(Map.of("erreur", "Accès refusé."));
    }
    emailService.envoyerAlerteBackup(horodatage);
    return ResponseEntity.ok(Map.of("ok", true));
  }
}
