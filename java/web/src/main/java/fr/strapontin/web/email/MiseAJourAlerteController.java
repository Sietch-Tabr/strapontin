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
 * Endpoint interne appelé par le module de mise à jour en fin d'opération :
 * journal d'administration et alerte e-mail.
 *
 * @since 3.2.0
 * @author Kwaite
 */
@RestController
public class MiseAJourAlerteController {

  private static final String RESULTAT_REUSSI = "reussi";
  private static final String RESULTAT_ECHEC = "echec";
  private static final int VERSION_MAX = 40;
  private static final int MESSAGE_MAX = 500;

  private final AlerteMiseAJourService alerteService;
  private final LogAdminService logAdminService;
  private final String secret;

  /**
   * @param alerteService alerte e-mail
   * @param logAdminService journal d'administration
   * @param secret secret partagé attendu dans l'en-tête {@code Authorization}
   */
  public MiseAJourAlerteController(
      final AlerteMiseAJourService alerteService,
      final LogAdminService logAdminService,
      @Value("${UPDATE_INTERNAL_SECRET:}") final String secret) {
    this.alerteService = alerteService;
    this.logAdminService = logAdminService;
    this.secret = secret;
  }

  /**
   * Reçoit l'issue d'une mise à jour (200, 400 si inconnue, 403 sans jeton).
   *
   * @param resultat {@code reussi} ou {@code echec}
   * @param version version visée
   * @param message détail rapporté par le module
   * @param authorization en-tête Bearer UPDATE_INTERNAL_SECRET
   */
  @PostMapping("/api/interne/mise-a-jour-statut")
  public ResponseEntity<Map<String, Object>> signalerStatut(
      @RequestParam(defaultValue = "") final String resultat,
      @RequestParam(defaultValue = "") final String version,
      @RequestParam(defaultValue = "") final String message,
      @RequestHeader(value = "Authorization", defaultValue = "") final String authorization) {
    if (!JetonInterne.estValide(secret, authorization)) {
      return JetonInterne.refus();
    }
    final boolean reussie = RESULTAT_REUSSI.equals(resultat);
    if (!reussie && !RESULTAT_ECHEC.equals(resultat)) {
      return ResponseEntity.badRequest().body(Map.of("erreur", "Résultat inconnu."));
    }
    final String versionBornee = borner(version, VERSION_MAX);
    final String messageBorne = borner(message, MESSAGE_MAX);
    logAdminService.logAdmin(
        reussie ? "info" : "error",
        "web",
        reussie ? "mise à jour installée" : "mise à jour en échec",
        Map.of("version", versionBornee, "message", messageBorne));
    alerteService.envoyer(reussie, versionBornee, messageBorne);
    return ResponseEntity.ok(Map.of("ok", true));
  }

  private static String borner(final String texte, final int max) {
    return texte.length() > max ? texte.substring(0, max) : texte;
  }
}
