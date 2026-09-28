/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.securite;

import fr.strapontin.web.utilisateur.ParametresService;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Retourne les données de session nécessaires au layout Vue (nom, rôle, badge).
 *
 * @since 2.2.0
 * @author Kwaite
 */
@RestController
public class SessionController {

  private final ParametresService parametresService;

  /**
   * @param parametresService lecture du compteur de nouveaux matches
   */
  public SessionController(final ParametresService parametresService) {
    this.parametresService = parametresService;
  }

  /**
   * Retourne les données de navigation de l'utilisateur courant.
   *
   * @param auth l'authentification Spring Security
   * @return les données de session : rôle, nbNouveau, changement de mot de passe exigé
   */
  @GetMapping("/api/session")
  public SessionDto session(final Authentication auth) {
    if (auth == null
        || !auth.isAuthenticated()
        || !(auth.getPrincipal() instanceof UtilisateurDetails ud)) {
      return new SessionDto("user", 0, false);
    }
    return new SessionDto(
        ud.role(),
        parametresService.compterNouveauxMatchesArtistes(ud.id()),
        parametresService.estChangementMotDePasseExige(ud.id()));
  }

  /**
   * DTO de session pour le layout Vue.
   *
   * @param role le rôle ({@code user} ou {@code admin})
   * @param nbNouveau le nombre de concerts non encore vus
   * @param changementMotDePasseExige {@code true} si un nouveau mot de passe est
   *     exigé (communication d'incident)
   */
  public record SessionDto(String role, int nbNouveau, boolean changementMotDePasseExige) {}
}
