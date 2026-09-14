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
 * Endpoint interne appelé par {@code scripts/aide-check.sh}
 * pour signaler le statut AIDE quotidien (détail : CLAUDE.md,
 * Sécurité admin/sessions).
 * @since 2.7.3
 * @author Kwaite
 */
@RestController
public class IntegriteAlerteController {

  private static final String STATUT_MODIFIE = "modifie";

  private final EmailAlerteService emailAlerteService;
  private final LogAdminService logAdminService;
  private final String integriteSecret;

  /**
   * @param emailAlerteService résolution du destinataire d'alerte
   * @param logAdminService journalisation du statut reçu
   * @param integriteSecret secret partagé attendu dans l'en-tête
   *     {@code Authorization}
   */
  public IntegriteAlerteController(
      final EmailAlerteService emailAlerteService,
      final LogAdminService logAdminService,
      @Value("${INTEGRITY_INTERNAL_SECRET:}") final String integriteSecret) {
    this.emailAlerteService = emailAlerteService;
    this.logAdminService = logAdminService;
    this.integriteSecret = integriteSecret;
  }

  /**
   * Notifie l'app du statut AIDE (détail : CLAUDE.md, Sécurité
   * admin/sessions).
   * @param statut {@code ok} ou {@code modifie}
   * @param detail sortie résumée d'{@code aide --check} (optionnel)
   * @param authorization header Bearer INTEGRITY_INTERNAL_SECRET
   * @return 200 si traité, 403 si le token est absent/invalide
   */
  @PostMapping("/api/interne/integrite-statut")
  public ResponseEntity<Map<String, Object>> signalerStatut(
      @RequestParam(defaultValue = "") final String statut,
      @RequestParam(defaultValue = "") final String detail,
      @RequestHeader(value = "Authorization", defaultValue = "") final String authorization) {
    final String attendu = "Bearer " + integriteSecret;
    if (integriteSecret.isBlank()
        || !MessageDigest.isEqual(
            attendu.getBytes(StandardCharsets.UTF_8),
            authorization.getBytes(StandardCharsets.UTF_8))) {
      return ResponseEntity.status(403).body(Map.of("erreur", "Accès refusé."));
    }
    if (STATUT_MODIFIE.equals(statut)) {
      logAdminService.logAdmin(
          "error", "web", "contrôle d'intégrité — modification détectée", Map.of("detail", detail));
      emailAlerteService.envoyerAlerteIntegrite(detail);
    } else {
      logAdminService.logAdmin(
          "info", "web", "contrôle d'intégrité — aucune modification", Map.of());
    }
    return ResponseEntity.ok(Map.of("ok", true));
  }
}
