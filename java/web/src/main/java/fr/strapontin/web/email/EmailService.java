/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.email;

import fr.strapontin.web.config.ConfigService;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/// Service d'email transactionnel via Resend.
///
/// <p>Si `RESEND_API_KEY` est absent de la configuration, toutes les méthodes
/// retournent silencieusement sans erreur.
///
/// <p>Les corps HTML sont générés par {@link EmailTemplates}. Les alertes système
/// (bounce/spam/backup/watchdog/logs-admin) sont dans {@link EmailAlerteService};
/// l'infrastructure d'envoi Resend partagée par les deux est dans {@link EmailSender}.
///
/// @since 2.0.0
/// @author Kwaite
@Service
public class EmailService {

  private static final Logger log = LoggerFactory.getLogger(EmailService.class);

  private final ConfigService configService;
  private final EmailSender emailSender;

  EmailService(ConfigService configService, EmailSender emailSender) {
    this.configService = configService;
    this.emailSender = emailSender;
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

  /// Envoie un email d'invitation à un nouvel utilisateur.
  ///
  /// @param email     l'adresse email du destinataire
  /// @param lienReset le lien de création de compte (valable 7 jours)
  public void envoyerInvitation(String email, String lienReset) {
    Optional<EmailSender.ContexteEmail> ctx = emailSender.obtenirContexte();
    if (ctx.isEmpty()) {
      log.warn("RESEND_API_KEY absent — email invitation non envoyé");
      return;
    }

    String corps = EmailTemplates.invitation(lienReset, ctx.get().baseUrl());

    // corps texte brut corrigé à "7 jours" — copié depuis le gabarit de confirmation de
    // changement d'email (celui-là bien à 24h) sans être adapté ; incohérent avec le HTML
    // (EmailTemplates.invitation) et la durée réelle du token (AdminService.creerUtilisateur,
    // Instant.now().plus(7, ChronoUnit.DAYS)).
    String texte =
        """
        Bienvenue sur Strapontin

        Votre compte a été créé. Cliquez sur le lien ci-dessous pour choisir
        votre identifiant et votre mot de passe (lien valable 7 jours) :

        %s
        """
            .formatted(lienReset);

    try {
      emailSender.envoyer(
          ctx.get(), email, ctx.get().expediteur(), "Votre invitation Strapontin", corps, texte);
      log.info("Invitation envoyée : emailHash={}", email.hashCode());
    } catch (Exception e) {
      log.error("Échec envoi invitation : emailHash={}", email.hashCode(), e);
    }
  }

  /// Envoie un email de réinitialisation de mot de passe.
  ///
  /// @param email     l'adresse email du destinataire
  /// @param lienReset le lien de réinitialisation (valable 1 heure)
  public void envoyerResetMotDePasse(String email, String lienReset) {
    Optional<EmailSender.ContexteEmail> ctx = emailSender.obtenirContexte();
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
      emailSender.envoyer(
          ctx.get(),
          email,
          ctx.get().expediteur(),
          "Réinitialisation de votre mot de passe Strapontin",
          corps,
          texte);
      log.info("Email reset MDP envoyé : emailHash={}", email.hashCode());
    } catch (Exception e) {
      log.error("Échec envoi reset MDP : emailHash={}", email.hashCode(), e);
    }
  }

  /// Notifie un utilisateur que sa double authentification a été réinitialisée par un admin.
  ///
  /// @param email l'adresse de l'utilisateur concerné
  public void envoyer2faReinitialisee(String email) {
    Optional<EmailSender.ContexteEmail> ctx = emailSender.obtenirContexte();
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
      emailSender.envoyer(
          ctx.get(),
          email,
          ctx.get().expediteur(),
          "Votre double authentification Strapontin a été réinitialisée",
          corps,
          texte);
      log.info("Email reset 2FA envoyé : emailHash={}", email.hashCode());
    } catch (Exception e) {
      log.error("Échec envoi reset 2FA : emailHash={}", email.hashCode(), e);
    }
  }

  /// Envoie un email de confirmation de changement d'adresse email.
  ///
  /// @param email le nouvel email destinataire
  /// @param lien  le lien de confirmation (valable 24 heures)
  public void envoyerConfirmationEmail(String email, String lien) {
    Optional<EmailSender.ContexteEmail> ctx = emailSender.obtenirContexte();
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
      emailSender.envoyer(
          ctx.get(),
          email,
          ctx.get().expediteur(),
          "Confirmez votre nouvelle adresse email — Strapontin",
          corps,
          texte);
      log.info("Confirmation email envoyée : emailHash={}", email.hashCode());
    } catch (Exception e) {
      log.error("Échec envoi confirmation email : emailHash={}", email.hashCode(), e);
    }
  }

  /// Indique si le service email est disponible (clé Resend configurée).
  ///
  /// @return `true` si `RESEND_API_KEY` est présent en configuration
  public boolean estDisponible() {
    return configService.lireConfig("RESEND_API_KEY").isPresent();
  }

  /// Envoie un signalement de salle manquante à l'admin, puis une confirmation à l'utilisateur.
  ///
  /// @param nom             le nom de la salle signalée
  /// @param ville           la ville/code postal (peut être vide)
  /// @param urlSite         l'URL du site ou de la billetterie (peut être vide)
  /// @param commentaire     le commentaire libre (peut être vide)
  /// @param emailUtilisateur l'adresse email de l'utilisateur connecté
  public void envoyerSignalementSalleManquante(
      String nom, String ville, String urlSite, String commentaire, String emailUtilisateur) {
    Optional<EmailSender.ContexteEmail> ctx = emailSender.obtenirContexte();
    if (ctx.isEmpty()) {
      log.warn("RESEND_API_KEY absent — signalement salle manquante non envoyé");
      return;
    }

    String corpsAdmin =
        EmailTemplates.signalementSalleManquante(
            nom, ville, urlSite, commentaire, emailUtilisateur, ctx.get().baseUrl());
    String texteAdmin =
        "Salle manquante signalée par %s%nNom : %s%nVille : %s%nSite : %s%n%n%s"
            .formatted(emailUtilisateur, nom, ville, urlSite, commentaire);

    try {
      emailSender.envoyer(
          ctx.get(),
          ctx.get().expediteur(),
          emailUtilisateur,
          "[Strapontin] Salle manquante — " + nom,
          corpsAdmin,
          texteAdmin);
      log.info(
          "Signalement salle manquante envoyé par : emailHash={}", emailUtilisateur.hashCode());
    } catch (Exception e) {
      log.error("Échec envoi signalement salle manquante", e);
      return;
    }

    String corpsUser = EmailTemplates.confirmationSignalementSalle(nom, ctx.get().baseUrl());
    String texteUser =
        "Votre signalement pour « %s » a été transmis à l'administrateur.".formatted(nom);

    try {
      emailSender.envoyer(
          ctx.get(),
          emailUtilisateur,
          ctx.get().expediteur(),
          "Votre signalement Strapontin a été transmis",
          corpsUser,
          texteUser);
      log.info(
          "Confirmation signalement salle envoyée : emailHash={}", emailUtilisateur.hashCode());
    } catch (Exception e) {
      log.error(
          "Échec envoi confirmation signalement salle : emailHash={}",
          emailUtilisateur.hashCode(),
          e);
    }
  }

  /// Envoie un signalement d'utilisateur à l'adresse de contact.
  ///
  /// @param type            le type (`bug`, `suggestion`, `question`)
  /// @param message         le message
  /// @param expediteurEmail l'email de l'expéditeur
  public void envoyerSignalement(String type, String message, String expediteurEmail) {
    Optional<EmailSender.ContexteEmail> ctx = emailSender.obtenirContexte();
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
      emailSender.envoyer(
          ctx.get(),
          ctx.get().expediteur(),
          expediteurEmail,
          "[Strapontin] Signalement — %s".formatted(typeLabel),
          corps,
          texte);
      log.info("Signalement envoyé : emailHash={}", expediteurEmail.hashCode());
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

    Optional<EmailSender.ContexteEmail> ctx = emailSender.obtenirContexte();
    if (ctx.isEmpty()) {
      log.warn("RESEND_API_KEY absent — digest concerts non envoyé");
      return false;
    }

    int nb = concerts.size();
    String sujet =
        nb == 1
            ? "1 nouveau concert correspond à vos préférences"
            : "%d nouveaux concerts correspondent à vos préférences".formatted(nb);

    List<LigneConcert> lignes =
        concerts.stream()
            .map(
                c ->
                    new LigneConcert(
                        c.artiste(),
                        formaterDate(c.date()),
                        c.ville() != null ? c.salle() + " · " + c.ville() : c.salle(),
                        c.url()))
            .toList();
    String corps = EmailTemplates.digestConcerts(sujet, lignes, ctx.get().baseUrl());

    try {
      emailSender.envoyer(ctx.get(), email, ctx.get().expediteur(), sujet, corps, sujet);
      log.info("Digest envoyé : emailHash={} ({} concert(s))", email.hashCode(), nb);
      return true;
    } catch (Exception e) {
      log.error("Échec envoi digest : emailHash={}", email.hashCode(), e);
      return false;
    }
  }

  /// Notifie un utilisateur que son compte a été supprimé par un administrateur.
  ///
  /// @param email l'adresse de l'utilisateur supprimé
  public void envoyerSuppressionCompte(String email) {
    Optional<EmailSender.ContexteEmail> ctx = emailSender.obtenirContexte();
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
      emailSender.envoyer(
          ctx.get(),
          email,
          ctx.get().expediteur(),
          "Votre compte Strapontin a été supprimé",
          corps,
          texte);
      log.info("Email suppression compte envoyé : emailHash={}", email.hashCode());
    } catch (Exception e) {
      log.error("Échec envoi suppression compte : emailHash={}", email.hashCode(), e);
    }
  }

  /// Envoie un email de test pour vérifier la configuration Resend.
  ///
  /// @param emailAdmin l'adresse email de l'administrateur destinataire
  /// @return {@code true} si l'email a été envoyé avec succès
  public boolean envoyerEmailTest(String emailAdmin) {
    Optional<EmailSender.ContexteEmail> ctx = emailSender.obtenirContexte();
    if (ctx.isEmpty()) {
      log.warn("RESEND_API_KEY absent — email de test non envoyé");
      return false;
    }
    try {
      emailSender.envoyer(
          ctx.get(),
          emailAdmin,
          ctx.get().expediteur(),
          "Strapontin — test de configuration email",
          EmailTemplates.emailTest(ctx.get().baseUrl()),
          "Test de configuration email Strapontin. Si vous recevez cet email, la configuration"
              + " Resend est correcte.");
      log.info("Email de test envoyé : emailHash={}", emailAdmin.hashCode());
      return true;
    } catch (Exception e) {
      log.error("Échec envoi email de test : emailHash={}", emailAdmin.hashCode(), e);
      return false;
    }
  }

  /// Notifie un utilisateur qu'il a été promu au rôle administrateur.
  ///
  /// @param email l'adresse de l'utilisateur promu
  public void envoyerPromotionAdmin(String email) {
    Optional<EmailSender.ContexteEmail> ctx = emailSender.obtenirContexte();
    if (ctx.isEmpty()) {
      log.warn("RESEND_API_KEY absent — email promotion admin non envoyé");
      return;
    }

    String corps = EmailTemplates.promotionAdmin(ctx.get().baseUrl());

    String texte =
        """
        Vous êtes maintenant administrateur Strapontin

        Votre compte a été promu au rôle administrateur.
        Accédez au panneau d'administration : %s/admin
        """
            .formatted(ctx.get().baseUrl());

    try {
      emailSender.envoyer(
          ctx.get(),
          email,
          ctx.get().expediteur(),
          "Vous êtes maintenant administrateur Strapontin",
          corps,
          texte);
      log.info("Email promotion admin envoyé : emailHash={}", email.hashCode());
    } catch (Exception e) {
      log.error("Échec envoi email promotion admin : emailHash={}", email.hashCode(), e);
    }
  }

  /// Envoie un préavis de révocation du rôle administrateur (délai de 7 jours).
  ///
  /// @param email         l'adresse de l'administrateur concerné
  /// @param dateEffective la date effective de révocation formatée (ex. "22 juin 2026")
  public void envoyerPreavisRevocationAdmin(String email, String dateEffective) {
    Optional<EmailSender.ContexteEmail> ctx = emailSender.obtenirContexte();
    if (ctx.isEmpty()) {
      log.warn("RESEND_API_KEY absent — email préavis révocation admin non envoyé");
      return;
    }

    String corps = EmailTemplates.preavisRevocationAdmin(dateEffective, ctx.get().baseUrl());

    String texte =
        """
        Préavis de révocation admin

        Votre accès administrateur Strapontin sera supprimé le %s.
        Si vous pensez qu'il s'agit d'une erreur, contactez un autre administrateur.
        """
            .formatted(dateEffective);

    try {
      emailSender.envoyer(
          ctx.get(),
          email,
          ctx.get().expediteur(),
          "Strapontin — préavis de révocation admin",
          corps,
          texte);
      log.info("Email préavis révocation admin envoyé : emailHash={}", email.hashCode());
    } catch (Exception e) {
      log.error("Échec envoi préavis révocation admin : emailHash={}", email.hashCode(), e);
    }
  }

  private static String formaterDate(String dateIso) {
    LocalDate date = LocalDate.parse(dateIso);
    return DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL)
        .withLocale(Locale.FRANCE)
        .format(date);
  }
}
