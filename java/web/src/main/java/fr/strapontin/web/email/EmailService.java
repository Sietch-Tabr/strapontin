/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.email;

import com.resend.Resend;
import com.resend.core.exception.ResendException;
import com.resend.services.emails.model.CreateEmailOptions;
import fr.strapontin.web.config.ConfigService;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/// Service d'email transactionnel via Resend.
///
/// <p>Si `RESEND_API_KEY` est absent de la configuration, toutes les méthodes
/// retournent silencieusement sans erreur.
///
/// <p>Les corps HTML sont générés par {@link EmailTemplates}.
///
/// @since 2.0.0
/// @author Kwaite
@Service
public class EmailService {

  private static final Logger log = LoggerFactory.getLogger(EmailService.class);

  private final ConfigService configService;
  private final JdbcTemplate jdbcTemplate;

  public EmailService(ConfigService configService, JdbcTemplate jdbcTemplate) {
    this.configService = configService;
    this.jdbcTemplate = jdbcTemplate;
  }

  /// Paramètres d'un concert pour le digest email.
  ///
  /// @param artiste le nom de l'artiste
  /// @param date    la date ISO (yyyy-MM-dd)
  /// @param salle   le nom de la salle
  /// @param ville   la ville (peut être nulle)
  /// @param url     l'URL de billetterie (peut être nulle)
  public record ConcertDigest(
      String artiste, String date, String salle, String ville, String url) {}

  private record ContexteEmail(Resend client, String expediteur, String baseUrl) {}

  private Optional<ContexteEmail> obtenirContexte() {
    Optional<String> cle = configService.lireConfig("RESEND_API_KEY");
    if (cle.isEmpty()) {
      return Optional.empty();
    }
    String expediteur =
        configService.lireConfig("CONTACT_EMAIL").or(this::obtenirEmailPremierAdmin).orElse("");
    String baseUrl = configService.lireConfig("PUBLIC_BASE_URL").orElse("");
    return Optional.of(new ContexteEmail(new Resend(cle.get()), expediteur, baseUrl));
  }

  private Optional<String> obtenirEmailPremierAdmin() {
    try {
      return Optional.ofNullable(
          jdbcTemplate.queryForObject(
              "SELECT email FROM users WHERE role = 'admin' ORDER BY created_at LIMIT 1",
              String.class));
    } catch (EmptyResultDataAccessException e) {
      return Optional.empty();
    }
  }

  private void envoyer(
      ContexteEmail ctx,
      String destinataire,
      String replyTo,
      String sujet,
      String corps,
      String texte) {
    try {
      CreateEmailOptions options =
          CreateEmailOptions.builder()
              .from(ctx.expediteur())
              .replyTo(replyTo)
              .to(List.of(destinataire))
              .subject(sujet)
              .html(corps)
              .text(texte)
              .build();
      ctx.client().emails().send(options);
    } catch (ResendException e) {
      throw new IllegalStateException("Erreur d'envoi email", e);
    }
  }

  /// Envoie un email d'invitation à un nouvel utilisateur.
  ///
  /// @param email     l'adresse email du destinataire
  /// @param lienReset le lien de création de compte (valable 24 heures)
  public void envoyerInvitation(String email, String lienReset) {
    Optional<ContexteEmail> ctx = obtenirContexte();
    if (ctx.isEmpty()) {
      log.warn("RESEND_API_KEY absent — email invitation non envoyé");
      return;
    }

    String corps = EmailTemplates.invitation(lienReset, ctx.get().baseUrl());

    String texte =
        """
        Bienvenue sur Strapontin

        Votre compte a été créé. Cliquez sur le lien ci-dessous pour choisir
        votre identifiant et votre mot de passe (lien valable 24 heures) :

        %s
        """
            .formatted(lienReset);

    try {
      envoyer(
          ctx.get(), email, ctx.get().expediteur(), "Votre invitation Strapontin", corps, texte);
      log.info("Invitation envoyée : {}", email);
    } catch (Exception e) {
      log.error("Échec envoi invitation : {}", email, e);
    }
  }

  /// Envoie un email de réinitialisation de mot de passe.
  ///
  /// @param email     l'adresse email du destinataire
  /// @param lienReset le lien de réinitialisation (valable 1 heure)
  public void envoyerResetMotDePasse(String email, String lienReset) {
    Optional<ContexteEmail> ctx = obtenirContexte();
    if (ctx.isEmpty()) {
      log.warn("RESEND_API_KEY absent — email reset non envoyé");
      return;
    }

    String corps = EmailTemplates.resetMotDePasse(lienReset, ctx.get().baseUrl());

    String texte =
        """
        Réinitialisation du mot de passe

        Cliquez sur ce lien pour réinitialiser votre mot de passe (valable 1 heure) :

        %s

        Si vous n'êtes pas à l'origine de cette demande, ignorez cet email.
        """
            .formatted(lienReset);

    try {
      envoyer(
          ctx.get(),
          email,
          ctx.get().expediteur(),
          "Réinitialisation de votre mot de passe Strapontin",
          corps,
          texte);
      log.info("Email reset MDP envoyé : {}", email);
    } catch (Exception e) {
      log.error("Échec envoi reset MDP : {}", email, e);
    }
  }

  /// Notifie un utilisateur que sa double authentification a été réinitialisée par un admin.
  ///
  /// @param email l'adresse de l'utilisateur concerné
  public void envoyer2faReinitialisee(String email) {
    Optional<ContexteEmail> ctx = obtenirContexte();
    if (ctx.isEmpty()) {
      log.warn("RESEND_API_KEY absent — email reset 2FA non envoyé");
      return;
    }

    String corps = EmailTemplates.reinitialisation2fa(ctx.get().baseUrl());

    String texte =
        """
        Votre double authentification a été réinitialisée

        Un administrateur a réinitialisé la double authentification (2FA) de votre compte.
        Vous pouvez vous reconnecter sans code, puis la reconfigurer depuis vos paramètres :

        %s/parametres/totp

        Si vous n'êtes pas à l'origine de cette action, contactez l'administrateur.
        """
            .formatted(ctx.get().baseUrl());

    try {
      envoyer(
          ctx.get(),
          email,
          ctx.get().expediteur(),
          "Votre double authentification Strapontin a été réinitialisée",
          corps,
          texte);
      log.info("Email reset 2FA envoyé : {}", email);
    } catch (Exception e) {
      log.error("Échec envoi reset 2FA : {}", email, e);
    }
  }

  /// Envoie un email de confirmation de changement d'adresse email.
  ///
  /// @param email le nouvel email destinataire
  /// @param lien  le lien de confirmation (valable 24 heures)
  public void envoyerConfirmationEmail(String email, String lien) {
    Optional<ContexteEmail> ctx = obtenirContexte();
    if (ctx.isEmpty()) {
      log.warn("RESEND_API_KEY absent — confirmation email non envoyée");
      return;
    }

    String corps = EmailTemplates.confirmationEmail(lien, ctx.get().baseUrl());

    String texte =
        """
        Confirmation de votre nouvelle adresse email

        Cliquez sur ce lien pour valider votre nouvelle adresse (valable 24 heures) :

        %s

        Si vous n'êtes pas à l'origine de cette demande, ignorez cet email.
        """
            .formatted(lien);

    try {
      envoyer(
          ctx.get(),
          email,
          ctx.get().expediteur(),
          "Confirmez votre nouvelle adresse email — Strapontin",
          corps,
          texte);
      log.info("Confirmation email envoyée : {}", email);
    } catch (Exception e) {
      log.error("Échec envoi confirmation email : {}", email, e);
    }
  }

  /// Indique si le service email est disponible (clé Resend configurée).
  ///
  /// @return `true` si `RESEND_API_KEY` est présent en configuration
  public boolean estDisponible() {
    return configService.lireConfig("RESEND_API_KEY").isPresent();
  }

  /// Envoie un signalement d'utilisateur à l'adresse de contact.
  ///
  /// @param type            le type (`bug`, `suggestion`, `question`)
  /// @param message         le message
  /// @param expediteurEmail l'email de l'expéditeur
  public void envoyerSignalement(String type, String message, String expediteurEmail) {
    Optional<ContexteEmail> ctx = obtenirContexte();
    if (ctx.isEmpty()) {
      log.warn("RESEND_API_KEY absent — signalement non envoyé");
      return;
    }

    String typeLabel =
        switch (type) {
          case "bug" -> "Bug";
          case "suggestion" -> "Amélioration";
          case "question" -> "Question";
          default -> type;
        };

    String corps =
        EmailTemplates.signalement(typeLabel, expediteurEmail, message, ctx.get().baseUrl());

    String texte =
        "Signalement — %s%n%nEmail : %s%n%n%s".formatted(typeLabel, expediteurEmail, message);

    try {
      envoyer(
          ctx.get(),
          ctx.get().expediteur(),
          expediteurEmail,
          "[Strapontin] Signalement — %s".formatted(typeLabel),
          corps,
          texte);
      log.info("Signalement envoyé : {}", expediteurEmail);
    } catch (Exception e) {
      log.error("Échec envoi signalement", e);
    }
  }

  /// Envoie un digest de nouveaux concerts à un utilisateur.
  ///
  /// @param email    l'adresse email du destinataire
  /// @param concerts la liste des concerts à notifier (doit être non vide)
  /// @return `true` si l'email a été envoyé avec succès
  public boolean envoyerDigestConcerts(String email, List<ConcertDigest> concerts) {
    if (concerts.isEmpty()) return false;

    Optional<ContexteEmail> ctx = obtenirContexte();
    if (ctx.isEmpty()) {
      log.warn("RESEND_API_KEY absent — digest concerts non envoyé");
      return false;
    }

    int nb = concerts.size();
    String sujet =
        nb == 1
            ? "1 nouveau concert correspond à vos préférences"
            : "%d nouveaux concerts correspondent à vos préférences".formatted(nb);

    List<Map<String, Object>> lignes =
        concerts.stream()
            .map(
                c -> {
                  Map<String, Object> ligne = new HashMap<>();
                  ligne.put("artiste", c.artiste());
                  ligne.put("dateFormatee", formaterDate(c.date()));
                  ligne.put("lieu", c.ville() != null ? c.salle() + " · " + c.ville() : c.salle());
                  if (c.url() != null) ligne.put("url", c.url());
                  return ligne;
                })
            .toList();
    String corps = EmailTemplates.digestConcerts(sujet, lignes, ctx.get().baseUrl());

    try {
      envoyer(ctx.get(), email, ctx.get().expediteur(), sujet, corps, sujet);
      log.info("Digest envoyé : {} ({} concert(s))", email, nb);
      return true;
    } catch (Exception e) {
      log.error("Échec envoi digest : {}", email, e);
      return false;
    }
  }

  /// Notifie un utilisateur que son compte a été supprimé par un administrateur.
  ///
  /// @param email l'adresse de l'utilisateur supprimé
  public void envoyerSuppressionCompte(String email) {
    Optional<ContexteEmail> ctx = obtenirContexte();
    if (ctx.isEmpty()) {
      log.warn("RESEND_API_KEY absent — email suppression compte non envoyé");
      return;
    }

    String corps = EmailTemplates.suppressionCompte(ctx.get().baseUrl());

    String texte =
        """
        Votre compte Strapontin a été supprimé

        Votre compte Strapontin a été supprimé par un administrateur.
        Vos données ont été effacées.

        Si vous pensez qu'il s'agit d'une erreur, contactez l'administrateur.
        """;

    try {
      envoyer(
          ctx.get(),
          email,
          ctx.get().expediteur(),
          "Votre compte Strapontin a été supprimé",
          corps,
          texte);
      log.info("Email suppression compte envoyé : {}", email);
    } catch (Exception e) {
      log.error("Échec envoi suppression compte : {}", email, e);
    }
  }

  /// Envoie une notification à l'admin quand un email Strapontin est rejeté en bounce.
  ///
  /// @param emailBouncee l'adresse email qui a produit le bounce
  public void envoyerNotificationBounce(String emailBouncee) {
    Optional<ContexteEmail> ctx = obtenirContexte();
    if (ctx.isEmpty()) {
      log.warn("RESEND_API_KEY absent — notification bounce non envoyée");
      return;
    }

    String corps = EmailTemplates.alerteBounce(emailBouncee, ctx.get().baseUrl());

    try {
      envoyer(
          ctx.get(),
          ctx.get().expediteur(),
          ctx.get().expediteur(),
          "Strapontin — bounce email : " + emailBouncee,
          corps,
          "Bounce détecté sur : "
              + emailBouncee
              + ". Consultez l'administration : "
              + ctx.get().baseUrl()
              + "/admin");
      log.info("Notification bounce envoyée pour : {}", emailBouncee);
    } catch (Exception e) {
      log.error("Échec envoi notification bounce", e);
    }
  }

  /// Envoie une alerte à l'admin quand le backup nocturne a échoué.
  ///
  /// @param horodatage l'horodatage du backup (format YYYYMMDD_HHMMSS), ou chaîne vide
  /// @param raison     la commande bash ayant déclenché l'échec, ou chaîne vide
  public void envoyerAlerteBackup(String horodatage, String raison) {
    Optional<ContexteEmail> ctx = obtenirContexte();
    if (ctx.isEmpty()) {
      log.warn("RESEND_API_KEY absent — alerte backup non envoyée");
      return;
    }

    String corps = EmailTemplates.alerteBackup(horodatage, raison, ctx.get().baseUrl());

    String sujet = "Strapontin — échec backup nocturne";
    String texte =
        "Échec du backup nocturne. Horodatage : %s.%s Consultez l'administration : %s/admin"
            .formatted(
                horodatage.isBlank() ? "inconnu" : horodatage,
                raison.isBlank() ? "" : " Raison : " + raison + ".",
                ctx.get().baseUrl());
    try {
      envoyer(ctx.get(), ctx.get().expediteur(), ctx.get().expediteur(), sujet, corps, texte);
      log.info("Alerte backup envoyée");
    } catch (Exception e) {
      log.error("Échec envoi alerte backup", e);
    }
  }

  /// Envoie un email de test pour vérifier la configuration Resend.
  ///
  /// @param emailAdmin l'adresse email de l'administrateur destinataire
  /// @return {@code true} si l'email a été envoyé avec succès
  public boolean envoyerEmailTest(String emailAdmin) {
    Optional<ContexteEmail> ctx = obtenirContexte();
    if (ctx.isEmpty()) {
      log.warn("RESEND_API_KEY absent — email de test non envoyé");
      return false;
    }
    try {
      envoyer(
          ctx.get(),
          emailAdmin,
          ctx.get().expediteur(),
          "Strapontin — test de configuration email",
          EmailTemplates.emailTest(ctx.get().baseUrl()),
          "Test de configuration email Strapontin. Si vous recevez cet email, la configuration"
              + " Resend est correcte.");
      log.info("Email de test envoyé : {}", emailAdmin);
      return true;
    } catch (Exception e) {
      log.error("Échec envoi email de test : {}", emailAdmin, e);
      return false;
    }
  }

  /// Envoie une alerte à l'admin quand le worker est inactif depuis plus de 3 heures.
  ///
  /// @param email               l'adresse email de l'administrateur
  /// @param derniereActiviteIso la date ISO du dernier job complété, ou null
  public void envoyerAlerteWorkerInactif(String email, String derniereActiviteIso) {
    Optional<ContexteEmail> ctx = obtenirContexte();
    if (ctx.isEmpty()) {
      log.warn("RESEND_API_KEY absent — alerte worker non envoyée");
      return;
    }

    String detail =
        derniereActiviteIso != null
            ? "Dernier job complété : %s.".formatted(derniereActiviteIso)
            : "Aucun job complété enregistré.";

    String corps = EmailTemplates.alerteWorker(detail, ctx.get().baseUrl());

    String sujet = "Strapontin — worker inactif";
    try {
      envoyer(
          ctx.get(),
          email,
          ctx.get().expediteur(),
          sujet,
          corps,
          "Worker inactif — %s Consultez l'administration : %s/admin"
              .formatted(detail, ctx.get().baseUrl()));
      log.info("Alerte worker envoyée : {}", email);
    } catch (Exception e) {
      log.error("Échec envoi alerte worker", e);
    }
  }

  private static String formaterDate(String dateIso) {
    LocalDate date = LocalDate.parse(dateIso);
    return DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL)
        .withLocale(Locale.FRANCE)
        .format(date);
  }
}
