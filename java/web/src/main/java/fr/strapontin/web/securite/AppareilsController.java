/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.securite;

import fr.strapontin.web.ErreurMetier;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Gestion des sessions actives ("appareils") de l'utilisateur courant, depuis /parametres.
 *
 * @since 2.7.8
 * @author Kwaite
 */
@RestController
@RequestMapping("/api/user/appareils")
public class AppareilsController {

  private final AppareilsService appareilsService;

  /**
   * @param appareilsService service de gestion des appareils
   */
  public AppareilsController(final AppareilsService appareilsService) {
    this.appareilsService = appareilsService;
  }

  /**
   * Liste les appareils (sessions actives) de l'utilisateur courant.
   *
   * @param auth contexte d'authentification
   * @param httpRequest la requête HTTP, pour identifier la session courante
   * @return les appareils actifs, le plus récemment actif en premier
   */
  @GetMapping
  public List<AppareilsService.AppareilDto> lister(
      final Authentication auth, final HttpServletRequest httpRequest) {
    final String userId = ((UtilisateurDetails) auth.getPrincipal()).id();
    final HttpSession session = httpRequest.getSession(false);
    return appareilsService.listerAppareils(userId, session != null ? session.getId() : null);
  }

  /**
   * Révoque un appareil (déconnecte la session correspondante).
   *
   * @param appareilId identifiant opaque de l'appareil à révoquer
   * @param auth contexte d'authentification
   * @return 204 si révoqué, 404 si l'appareil n'existe pas ou n'appartient pas à l'utilisateur
   */
  @DeleteMapping("/{appareilId}")
  public ResponseEntity<Void> revoquer(
      @PathVariable final String appareilId, final Authentication auth) {
    final String userId = ((UtilisateurDetails) auth.getPrincipal()).id();
    if (!appareilsService.revoquerAppareil(userId, appareilId)) {
      throw new ErreurMetier("Appareil introuvable.", 404);
    }
    return ResponseEntity.noContent().build();
  }
}
