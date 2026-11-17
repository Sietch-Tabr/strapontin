/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.miseajour;

import static fr.strapontin.web.ControllerUtils.utilisateur;

import fr.strapontin.web.admin.LogAdminService;
import fr.strapontin.web.securite.UtilisateurDetails;
import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Section « Mise à jour » de l'administration : version en service, version
 * proposée, état de la dernière mise à jour, demande d'installation. HTTP
 * pur, réservé au rôle ADMIN.
 *
 * @since 3.2.0
 * @author Kwaite
 */
@RestController
@RequestMapping("/api/admin/mise-a-jour")
public class MiseAJourAdminController {

  private final MiseAJourService service;
  private final ReauthentificationAdmin reauthentification;
  private final LogAdminService logAdminService;

  /**
   * @param service lecture de l'état et dépôt de la demande
   * @param reauthentification mot de passe et TOTP de l'administrateur
   * @param logAdminService journal d'administration
   */
  public MiseAJourAdminController(
      final MiseAJourService service,
      final ReauthentificationAdmin reauthentification,
      final LogAdminService logAdminService) {
    this.service = service;
    this.reauthentification = reauthentification;
    this.logAdminService = logAdminService;
  }

  /**
   * @param auth administrateur connecté
   * @return 200 avec les versions et l'état de la dernière mise à jour
   */
  @GetMapping
  public ResponseEntity<EtatMiseAJourWeb> lire(final Authentication auth) {
    return ResponseEntity.ok(service.etat(utilisateur(auth).twoFactorActif()));
  }

  /**
   * Demande l'installation de la version proposée.
   *
   * @param requete version, mot de passe et code TOTP
   * @param auth administrateur connecté
   * @return 202 si la demande est déposée ; 400, 409, 422 ou 429 sinon
   */
  @PostMapping
  public ResponseEntity<Map<String, Object>> demander(
      @Valid @RequestBody final RequeteMiseAJour requete, final Authentication auth) {
    final UtilisateurDetails admin = utilisateur(auth);
    reauthentification.exiger(admin, requete.motDePasse(), requete.codeTotp());
    service.demander(requete.version(), admin.identifiant());
    logAdminService.logAdmin(
        "info",
        "admin",
        "mise à jour demandée : " + requete.version(),
        Map.of("identifiant", admin.identifiant(), "version", requete.version()));
    return ResponseEntity.accepted().body(Map.of("ok", true));
  }
}
