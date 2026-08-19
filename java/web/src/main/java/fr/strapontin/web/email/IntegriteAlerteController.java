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

/// Endpoint interne appelé par {@code scripts/aide-check.sh} pour signaler le statut du contrôle
/// d'intégrité quotidien (AIDE) sur un LXC prod/dev.
///
/// <p>Protégé par un secret partagé : l'appelant doit présenter le header
/// {@code Authorization: Bearer <INTEGRITY_INTERNAL_SECRET>}. Si la variable
/// {@code INTEGRITY_INTERNAL_SECRET} est absente ou que le token ne correspond pas, l'accès est
/// refusé (403). Enregistre le statut dans {@code logs_admin} et envoie un email d'alerte sur
/// détection de modification.
///
/// @since 2.7.3
/// @author Kwaite
@RestController
public class IntegriteAlerteController {

  private final EmailAlerteService emailAlerteService;
  private final LogAdminService logAdminService;
  private final String integriteSecret;

  public IntegriteAlerteController(
      EmailAlerteService emailAlerteService,
      LogAdminService logAdminService,
      @Value("${INTEGRITY_INTERNAL_SECRET:}") String integriteSecret) {
    this.emailAlerteService = emailAlerteService;
    this.logAdminService = logAdminService;
    this.integriteSecret = integriteSecret;
  }

  /// Notifie l'app du statut du contrôle d'intégrité AIDE quotidien.
  ///
  /// <p>Sur {@code statut=modifie} : log error dans {@code logs_admin} + email admin. Sur
  /// {@code statut=ok} : log info dans {@code logs_admin}.
  ///
  /// @param statut        {@code ok} ou {@code modifie}
  /// @param detail        sortie résumée d'{@code aide --check} (optionnel, {@code modifie}
  /// uniquement)
  /// @param authorization header {@code Authorization: Bearer <INTEGRITY_INTERNAL_SECRET>}
  /// @return 200 si traité, 403 si le token est absent ou invalide
  @PostMapping("/api/interne/integrite-statut")
  public ResponseEntity<Map<String, Object>> signalerStatut(
      @RequestParam(defaultValue = "") String statut,
      @RequestParam(defaultValue = "") String detail,
      @RequestHeader(value = "Authorization", defaultValue = "") String authorization) {
    String attendu = "Bearer " + integriteSecret;
    if (integriteSecret.isBlank()
        || !MessageDigest.isEqual(
            attendu.getBytes(StandardCharsets.UTF_8),
            authorization.getBytes(StandardCharsets.UTF_8))) {
      return ResponseEntity.status(403).body(Map.of("erreur", "Accès refusé."));
    }
    if ("modifie".equals(statut)) {
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
