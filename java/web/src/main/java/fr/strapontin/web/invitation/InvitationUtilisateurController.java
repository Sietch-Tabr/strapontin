/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.invitation;

import static fr.strapontin.web.ControllerUtils.erreur;

import fr.strapontin.web.config.ConfigService;
import fr.strapontin.web.securite.UtilisateurDetails;
import jakarta.validation.Valid;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Invitations d'un utilisateur connecté : liste et état, envoi, renvoi, annulation.
 * @since 3.1.4
 * @author Kwaite
 */
@RestController
@RequestMapping("/api/invitations")
public class InvitationUtilisateurController {

  private static final String MESSAGE_SANS_URL =
      "L'envoi d'invitations n'est pas disponible pour le moment.";

  private final InvitationUtilisateurService service;
  private final ConfigService configService;

  /**
   * @param service service métier des invitations
   * @param configService lecture de PUBLIC_BASE_URL
   */
  public InvitationUtilisateurController(
      final InvitationUtilisateurService service, final ConfigService configService) {
    this.service = service;
    this.configService = configService;
  }

  /**
   * @param auth utilisateur connecté
   * @return plafond, invitations restantes et liste de ses invitations
   */
  @GetMapping
  public InvitationUtilisateurService.Etat etat(final Authentication auth) {
    return service.etat(utilisateur(auth).id());
  }

  /**
   * Invite une personne : la réponse est la même pour une adresse déjà inscrite.
   * @param requete adresse de la personne invitée
   * @param auth utilisateur connecté
   * @return 200 {@code {succes: true}}, 409 plafond atteint, 429 cadence dépassée, 503 sans URL
   *     publique
   */
  @PostMapping
  public ResponseEntity<Map<String, Object>> inviter(
      @Valid @RequestBody final RequeteInviter requete, final Authentication auth) {
    final Optional<String> baseUrl = baseUrl();
    if (baseUrl.isEmpty()) {
      return erreur(503, MESSAGE_SANS_URL);
    }
    service.inviter(utilisateur(auth).id(), requete.email(), baseUrl.get());
    return ResponseEntity.ok(Map.of("succes", true));
  }

  /**
   * @param id invitation à renvoyer
   * @param auth utilisateur connecté
   * @return 200 {@code {succes: true}}, 404, 429 ou 503
   */
  @PostMapping("/{id}/renvoyer")
  public ResponseEntity<Map<String, Object>> renvoyer(
      @PathVariable final UUID id, final Authentication auth) {
    final Optional<String> baseUrl = baseUrl();
    if (baseUrl.isEmpty()) {
      return erreur(503, MESSAGE_SANS_URL);
    }
    service.renvoyer(utilisateur(auth).id(), id, baseUrl.get());
    return ResponseEntity.ok(Map.of("succes", true));
  }

  /**
   * @param id invitation à annuler
   * @param auth utilisateur connecté
   * @return 200 {@code {succes: true}} ou 404
   */
  @DeleteMapping("/{id}")
  public ResponseEntity<Map<String, Object>> annuler(
      @PathVariable final UUID id, final Authentication auth) {
    service.annuler(utilisateur(auth).id(), id);
    return ResponseEntity.ok(Map.of("succes", true));
  }

  private Optional<String> baseUrl() {
    return configService.lireConfig("PUBLIC_BASE_URL").filter(s -> !s.isBlank());
  }

  private static UtilisateurDetails utilisateur(final Authentication auth) {
    return (UtilisateurDetails) auth.getPrincipal();
  }
}
