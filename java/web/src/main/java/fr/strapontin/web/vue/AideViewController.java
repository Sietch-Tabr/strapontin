/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.vue;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import fr.strapontin.web.config.ConfigService;
import fr.strapontin.web.email.EmailService;
import fr.strapontin.web.securite.UtilisateurDetails;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/// Contrôleur MVC Thymeleaf pour les pages statiques (aide, changelog).
///
/// @since 2.0.0
/// @author Kwaite
@Controller
public class AideViewController {

  private static final String EMAIL_CONTACT_DEFAUT = "admin@strapontin.kwaite.fr";
  private static final int MAX_LONGUEUR_MESSAGE = 2000;
  private static final int MAX_SIGNALEMENTS_PAR_HEURE = 3;
  private static final String VERSION_COURANTE = "2.0.5";

  private final EmailService emailService;
  private final ConfigService configService;
  private final NamedParameterJdbcTemplate jdbc;

  private final Cache<String, Bucket> bucketsSignalement =
      Caffeine.newBuilder()
          .expireAfterWrite(Duration.ofHours(1).plusMinutes(1))
          .maximumSize(10_000)
          .build();

  public AideViewController(
      EmailService emailService,
      ConfigService configService,
      NamedParameterJdbcTemplate jdbc) {
    this.emailService = emailService;
    this.configService = configService;
    this.jdbc = jdbc;
  }

  /// Affiche la page d'aide.
  ///
  /// @param modele le modèle Thymeleaf
  /// @return le nom du template
  @GetMapping("/aide")
  public String aide(Model modele) {
    modele.addAttribute("currentPage", "aide");
    modele.addAttribute("resendDisponible", emailService.estDisponible());
    modele.addAttribute(
        "contactEmail", configService.lireConfig("CONTACT_EMAIL").orElse(EMAIL_CONTACT_DEFAUT));
    return "aide";
  }

  /// Affiche le journal des modifications et marque la version comme vue.
  ///
  /// @param modele le modèle Thymeleaf
  /// @param auth   contexte d'authentification
  /// @return le nom du template
  @GetMapping("/changelog")
  public String changelog(Model modele, Authentication auth) {
    modele.addAttribute("currentPage", "changelog");
    if (auth != null && auth.getPrincipal() instanceof UtilisateurDetails utilisateur) {
      jdbc.update(
          "UPDATE user_config SET changelog_version_vue = :v WHERE user_id = :u",
          Map.of("v", VERSION_COURANTE, "u", utilisateur.id()));
    }
    return "changelog";
  }

  /// Envoie un signalement à l'adresse de contact.
  ///
  /// @param type      le type (`bug`, `suggestion`, `question`)
  /// @param message   le message de l'utilisateur (max 2000 caractères)
  /// @param redirects attributs de redirection
  /// @param auth      contexte d'authentification
  /// @param request   la requête HTTP (pour rate limiting par IP)
  /// @return redirect vers /aide
  @PostMapping("/aide/signalement")
  public String signaler(
      @RequestParam String type,
      @RequestParam String message,
      RedirectAttributes redirects,
      Authentication auth,
      HttpServletRequest request) {
    if (!(auth.getPrincipal() instanceof UtilisateurDetails utilisateur)) {
      throw new IllegalStateException("Principal inattendu");
    }

    String ip = request.getRemoteAddr();
    Bucket bucket = bucketsSignalement.get(ip, _ -> creerBucketSignalement());
    if (!bucket.tryConsume(1)) {
      redirects.addFlashAttribute(
          "erreurSignalement", "Trop de signalements. Réessayez dans une heure.");
      return "redirect:/aide";
    }

    String msg = message == null ? "" : message.strip();
    if (msg.isEmpty()) {
      redirects.addFlashAttribute("erreurSignalement", "Le message est requis.");
      return "redirect:/aide";
    }
    if (msg.length() > MAX_LONGUEUR_MESSAGE) {
      redirects.addFlashAttribute(
          "erreurSignalement",
          "Le message ne peut pas dépasser " + MAX_LONGUEUR_MESSAGE + " caractères.");
      return "redirect:/aide";
    }

    String typeValide = Set.of("bug", "suggestion", "question").contains(type) ? type : "question";
    emailService.envoyerSignalement(typeValide, msg, utilisateur.email(), utilisateur.nom());
    redirects.addFlashAttribute("succesSignalement", true);
    return "redirect:/aide";
  }

  private Bucket creerBucketSignalement() {
    Bandwidth limite =
        Bandwidth.builder()
            .capacity(MAX_SIGNALEMENTS_PAR_HEURE)
            .refillIntervally(MAX_SIGNALEMENTS_PAR_HEURE, Duration.ofHours(1))
            .build();
    return Bucket.builder().addLimit(limite).build();
  }
}
