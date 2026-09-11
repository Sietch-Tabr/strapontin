/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.utilisateur;

import static fr.strapontin.web.ControllerUtils.erreur;

import fr.strapontin.web.config.ConfigService;
import fr.strapontin.web.securite.UtilisateurDetails;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Endpoints REST pour les paramètres utilisateur non couverts par les contrôleurs existants.
 *
 * <p>Gère le changement d'email (flux de confirmation), les compteurs artistes/salles et la
 * confirmation du changement d'email depuis le lien envoyé par email.
 *
 * @since 2.2.0
 * @author Kwaite
 */
@RestController
@RequestMapping("/api/parametres")
public class ParametresRestController {

  private final ParametresService parametresService;
  private final ConfigService configService;

  /**
   * @param parametresService service métier des paramètres
   * @param configService service de configuration pour lire PUBLIC_BASE_URL
   */
  public ParametresRestController(
      final ParametresService parametresService, final ConfigService configService) {
    this.parametresService = parametresService;
    this.configService = configService;
  }

  /**
   * Retourne les compteurs d'artistes et de salles pour l'utilisateur courant.
   *
   * @param auth contexte d'authentification
   * @return {@code {nbArtistes, nbSalles}}
   */
  @GetMapping("/stats")
  public ResponseEntity<Map<String, Object>> stats(final Authentication auth) {
    return ResponseEntity.ok(parametresService.stats(utilisateur(auth).id()));
  }

  /**
   * Déclenche un flux de confirmation pour un changement d'adresse email.
   *
   * <p>Envoie un email de confirmation à la nouvelle adresse. L'email est mis à jour uniquement
   * après validation du lien (24 h) via {@link #confirmerEmail}.
   *
   * @param corps corps JSON avec {@code email} (nouvelle adresse)
   * @param auth contexte d'authentification
   * @return 200 {@code {succes: true}} ou 400/503 {@code {erreur: "..."}}
   */
  @PostMapping("/email-change")
  public ResponseEntity<Map<String, Object>> emailChange(
      @Valid @RequestBody final RequeteEmailChange corps, final Authentication auth) {
    final UtilisateurDetails details = utilisateur(auth);
    final String emailNorm = corps.email().strip().toLowerCase();
    if (emailNorm.equalsIgnoreCase(details.email())) {
      return erreur(400, "Cette adresse est déjà votre adresse email actuelle.");
    }
    if (parametresService.emailDejaUtilise(emailNorm, details.id())) {
      return erreur(400, "Cette adresse email est déjà utilisée.");
    }
    if (!parametresService.emailDisponible()) {
      return erreur(
          503, "L'envoi d'email n'est pas configuré. Impossible de confirmer le changement.");
    }
    final Optional<String> baseUrl = resoudreBaseUrl();
    if (baseUrl.isEmpty()) {
      return erreur(
          503, "L'envoi d'email n'est pas configuré. Impossible de confirmer le changement.");
    }

    parametresService.demanderChangementEmail(details, emailNorm, baseUrl.get());
    return ResponseEntity.ok(Map.of("succes", true));
  }

  /**
   * Confirme le changement d'email via le token envoyé par email.
   *
   * <p>L'utilisateur doit être connecté (session active). En cas de succès, redirige vers
   * {@code /parametres?succes=email-confirme#compte}.
   *
   * @param token le token de confirmation (paramètre URL)
   * @param auth contexte d'authentification
   * @return redirect 302 vers {@code /parametres?succes=email-confirme#compte} ou une page d'erreur
   */
  @GetMapping("/confirmer-email")
  public ResponseEntity<Void> confirmerEmail(
      @RequestParam(required = false) final String token, final Authentication auth) {
    final String userIdAuthentifie = utilisateur(auth).id();
    final ParametresService.ResultatConfirmation resultat =
        parametresService.confirmerEmail(token, userIdAuthentifie);
    final String redirection =
        switch (resultat.statut()) {
          case OK -> "/parametres?succes=email-confirme#compte";
          case LIEN_INVALIDE -> "/parametres?erreur=lien-invalide#compte";
          case LIEN_EXPIRE -> "/parametres?erreur=lien-expire#compte";
          case MAUVAIS_UTILISATEUR -> "/parametres?erreur=lien-invalide#compte";
          case AUCUN_CHANGEMENT -> "/parametres?erreur=aucun-changement-email#compte";
          case EMAIL_DEJA_UTILISE -> "/parametres?erreur=email-deja-utilise#compte";
        };
    return ResponseEntity.status(302).location(URI.create(redirection)).build();
  }

  private static UtilisateurDetails utilisateur(final Authentication auth) {
    return (UtilisateurDetails) auth.getPrincipal();
  }

  /**
   * Résout l'URL de base publique pour construire le lien de confirmation envoyé par email.
   *
   * <p>Ne retombe jamais sur le header {@code Host}/{@code getServerName()} de la requête entrante
   * : un attaquant contrôlant ce header pourrait rediriger le lien de confirmation vers un domaine
   * sous son contrôle et voler le token dès qu'un utilisateur clique dessus (Host Header
   * Injection).
   *
   * @return l'URL configurée, vide si {@code PUBLIC_BASE_URL} n'est pas configurée
   */
  private Optional<String> resoudreBaseUrl() {
    return configService.lireConfig("PUBLIC_BASE_URL").filter(s -> !s.isBlank());
  }
}
