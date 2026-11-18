/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.email;

import fr.strapontin.web.admin.LogAdminService;
import fr.strapontin.web.miseajour.EtatMiseAJourWeb;
import fr.strapontin.web.miseajour.MiseAJourService;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * Mise à jour du dev sans interface : demande et état, protégés par le secret
 * interne et réservés au profil {@code dev}. Ni TOTP ni session ici : sur
 * la prod, ces routes n'existent pas.
 *
 * @since 3.2.0
 * @author Kwaite
 */
@Profile("dev")
@RestController
public class MiseAJourInterneController {

  private static final String DEMANDEUR = "api-dev";

  private final MiseAJourService service;
  private final LogAdminService logAdminService;
  private final String secret;

  /**
   * @param service lecture de l'état et dépôt de la demande
   * @param logAdminService journal d'administration
   * @param secret secret partagé attendu dans l'en-tête {@code Authorization}
   */
  public MiseAJourInterneController(
      final MiseAJourService service,
      final LogAdminService logAdminService,
      @Value("${UPDATE_INTERNAL_SECRET:}") final String secret) {
    this.service = service;
    this.logAdminService = logAdminService;
    this.secret = secret;
  }

  /**
   * Demande l'installation de la version proposée, sans la nommer.
   *
   * @param authorization en-tête Bearer UPDATE_INTERNAL_SECRET
   * @return 202 et la version demandée, 403 sans jeton valide
   */
  @PostMapping("/api/interne/mise-a-jour-demande")
  public ResponseEntity<Map<String, Object>> demander(
      @RequestHeader(value = "Authorization", defaultValue = "") final String authorization) {
    if (!JetonInterne.estValide(secret, authorization)) {
      return JetonInterne.refus();
    }
    final String version = service.demanderVersionProposee(DEMANDEUR);
    logAdminService.logAdmin(
        "info",
        "admin",
        "mise à jour demandée : " + version,
        Map.of("identifiant", DEMANDEUR, "version", version));
    return ResponseEntity.accepted().body(Map.of("version", version));
  }

  /**
   * Version en service, version proposée et dernier état.
   *
   * @param authorization en-tête Bearer UPDATE_INTERNAL_SECRET
   * @return 200 et l'état, 403 sans jeton valide
   */
  @GetMapping("/api/interne/mise-a-jour-etat")
  public ResponseEntity<?> etat(
      @RequestHeader(value = "Authorization", defaultValue = "") final String authorization) {
    if (!JetonInterne.estValide(secret, authorization)) {
      return JetonInterne.refus();
    }
    final EtatMiseAJourWeb etat = service.etat(true);
    return ResponseEntity.ok(etat);
  }
}
