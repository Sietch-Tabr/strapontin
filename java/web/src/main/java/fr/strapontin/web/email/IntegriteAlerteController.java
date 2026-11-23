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
  private static final String STATUT_ECART_SYSTEME = "ecart_systeme";

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
   * Notifie l'app du statut AIDE ou d'un écart du contrôle du système
   * (détail : CLAUDE.md, scripts d'exploitation).
   * @param statut {@code ok}, {@code modifie} ou {@code ecart_systeme}
   * @param detail sortie résumée d'{@code aide --check} ou lignes d'écart
   * @param authorization header Bearer INTEGRITY_INTERNAL_SECRET
   * @return 200 si traité, 403 si le token est absent/invalide
   */
  @PostMapping("/api/interne/integrite-statut")
  public ResponseEntity<Map<String, Object>> signalerStatut(
      @RequestParam(defaultValue = "") final String statut,
      @RequestParam(defaultValue = "") final String detail,
      @RequestHeader(value = "Authorization", defaultValue = "") final String authorization) {
    if (!JetonInterne.estValide(integriteSecret, authorization)) {
      return JetonInterne.refus();
    }
    if (STATUT_ECART_SYSTEME.equals(statut)) {
      logAdminService.logAdmin(
          "error", "web", "contrôle du système — écart détecté", Map.of("detail", detail));
    } else if (STATUT_MODIFIE.equals(statut)) {
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
