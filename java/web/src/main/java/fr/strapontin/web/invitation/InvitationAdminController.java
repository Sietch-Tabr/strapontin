/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.invitation;

import fr.strapontin.web.securite.UtilisateurDetails;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Réglages d'administration des invitations (réservé aux administrateurs par la
 * configuration de sécurité de {@code /api/admin}).
 * @since 3.1.4
 * @author Kwaite
 */
@RestController
@RequestMapping("/api/admin")
public class InvitationAdminController {

  private final InvitationAdminService service;

  /**
   * @param service réglages des invitations
   */
  public InvitationAdminController(final InvitationAdminService service) {
    this.service = service;
  }

  /**
   * Plafond par défaut.
   * @param plafond valeur, de 0 à {@link ReglagesInvitations#PLAFOND_MAX}
   */
  public record RequetePlafondDefaut(
      @NotNull(message = "Le plafond est requis.")
          @Min(value = 0, message = "Le plafond doit être positif ou nul.")
          @Max(value = ReglagesInvitations.PLAFOND_MAX, message = "Le plafond est trop élevé.")
          Integer plafond) {}

  /**
   * Plafond propre à un utilisateur.
   * @param plafond valeur, {@code null} pour revenir au plafond par défaut
   */
  public record RequetePlafondUtilisateur(
      @Min(value = 0, message = "Le plafond doit être positif ou nul.")
          @Max(value = ReglagesInvitations.PLAFOND_MAX, message = "Le plafond est trop élevé.")
          Integer plafond) {}

  /**
   * @return plafond par défaut et limites de cadence
   */
  @GetMapping("/invitations/reglages")
  public InvitationAdminService.Reglages reglages() {
    return service.reglages();
  }

  /**
   * @param requete nouveau plafond par défaut
   * @param auth administrateur connecté
   * @return 200 {@code {succes: true}}
   */
  @PutMapping("/invitations/plafond-defaut")
  public ResponseEntity<Map<String, Object>> definirPlafondDefaut(
      @Valid @RequestBody final RequetePlafondDefaut requete, final Authentication auth) {
    service.definirPlafondDefaut(
        requete.plafond(), ((UtilisateurDetails) auth.getPrincipal()).id());
    return ResponseEntity.ok(Map.of("succes", true));
  }

  /**
   * @param userId utilisateur concerné
   * @param requete plafond propre, nul pour le plafond par défaut
   * @param auth administrateur connecté
   * @return 200 {@code {succes: true}} ou 404
   */
  @PutMapping("/utilisateurs/{userId}/plafond-invitations")
  public ResponseEntity<Map<String, Object>> definirPlafondUtilisateur(
      @PathVariable final String userId,
      @Valid @RequestBody final RequetePlafondUtilisateur requete,
      final Authentication auth) {
    service.definirPlafondUtilisateur(
        userId, requete.plafond(), ((UtilisateurDetails) auth.getPrincipal()).id());
    return ResponseEntity.ok(Map.of("succes", true));
  }

  /**
   * Réinitialise la cadence d'envoi des invitations (profil {@code dev} uniquement) : appelé par
   * le global-setup E2E, comme le quota de signalement, pour repartir d'une cadence vide.
   *
   * @return 204
   */
  @Profile("dev")
  @DeleteMapping("/invitations/envois")
  public ResponseEntity<Void> reinitialiserEnvois() {
    service.reinitialiserEnvois();
    return ResponseEntity.noContent().build();
  }
}
