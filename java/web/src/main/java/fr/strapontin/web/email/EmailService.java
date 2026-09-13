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

/**
 * Service d'email transactionnel via Resend.
 *
 * <p>Si {@code RESEND_API_KEY} est absent de la configuration, toutes les méthodes retournent
 * silencieusement sans erreur.
 *
 * <p>Les corps HTML sont générés par {@link EmailTemplates}. Les alertes système
 * (bounce/spam/backup/watchdog/logs-admin) sont dans {@link EmailAlerteService}; l'infrastructure
 * d'envoi Resend partagée par les deux est dans {@link EmailSender}.
 *
 * @since 2.0.0
 * @author Kwaite
 */
@Service
public class EmailService {

  private static final Logger LOG = LoggerFactory.getLogger(EmailService.class);

  private final ConfigService configService;
  private final EmailSender emailSender;

  /* package-private : instancié uniquement par Spring (même package). */
  EmailService(final ConfigService configService, final EmailSender emailSender) {
    this.configService = configService;
    this.emailSender = emailSender;
  }

  /**
   * Paramètres d'un concert pour le digest email.
   *
   * @param artiste le nom de l'artiste
   * @param date la date ISO (yyyy-MM-dd)
   * @param salle le nom de la salle
   * @param ville la ville (peut être nulle)
   * @param url l'URL de billetterie (peut être nulle)
   */
  public record ConcertDigest(
      String artiste, String date, String salle, String ville, String url) {}

  /**
   * Envoie un email d'invitation à un nouvel utilisateur.
   *
   * @param email l'adresse email du destinataire
   * @param lienReset le lien de création de compte (valable 7 jours)
   */
  public void envoyerInvitation(final String email, final String lienReset) {
    final Optional<EmailSender.ContexteEmail> ctx = emailSender.obtenirContexte();
    if (ctx.isEmpty()) {
      LOG.warn("RESEND_API_KEY absent — email invitation non envoyé");
      return;
    }

    final String corps = EmailTemplates.invitation(lienReset, ctx.get().baseUrl());

    final String texte =
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
      LOG.info("Invitation envoyée : emailHash={}", email.hashCode());
    } catch (Exception e) {
      LOG.error("Échec envoi invitation : emailHash={}", email.hashCode(), e);
    }
  }

  /**
   * Envoie un email de réinitialisation de mot de passe.
   *
   * @param email l'adresse email du destinataire
   * @param lienReset le lien de réinitialisation (valable 1 heure)
   */
  public void envoyerResetMotDePasse(final String email, final String lienReset) {
    final Optional<EmailSender.ContexteEmail> ctx = emailSender.obtenirContexte();
    if (ctx.isEmpty()) {
      LOG.warn("RESEND_API_KEY absent — email reset non envoyé");
      return;
    }

    final String corps = EmailTemplates.resetMotDePasse(lienReset, ctx.get().baseUrl());

    final String texte =
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
      LOG.info("Email reset MDP envoyé : emailHash={}", email.hashCode());
    } catch (Exception e) {
      LOG.error("Échec envoi reset MDP : emailHash={}", email.hashCode(), e);
    }
  }

  /**
   * Notifie un utilisateur que sa double authentification a été réinitialisée par un admin.
   *
   * @param email l'adresse de l'utilisateur concerné
   */
  public void envoyer2faReinitialisee(final String email) {
    final Optional<EmailSender.ContexteEmail> ctx = emailSender.obtenirContexte();
    if (ctx.isEmpty()) {
      LOG.warn("RESEND_API_KEY absent — email reset 2FA non envoyé");
      return;
    }

    final String corps = EmailTemplates.reinitialisation2fa(ctx.get().baseUrl());

    final String texte =
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
      LOG.info("Email reset 2FA envoyé : emailHash={}", email.hashCode());
    } catch (Exception e) {
      LOG.error("Échec envoi reset 2FA : emailHash={}", email.hashCode(), e);
    }
  }

  /**
   * Envoie un email de confirmation de changement d'adresse email.
   *
   * @param email le nouvel email destinataire
   * @param lien le lien de confirmation (valable 24 heures)
   */
  public void envoyerConfirmationEmail(final String email, final String lien) {
    final Optional<EmailSender.ContexteEmail> ctx = emailSender.obtenirContexte();
    if (ctx.isEmpty()) {
      LOG.warn("RESEND_API_KEY absent — confirmation email non envoyée");
      return;
    }

    final String corps = EmailTemplates.confirmationEmail(lien, ctx.get().baseUrl());

    final String texte =
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
      LOG.info("Confirmation email envoyée : emailHash={}", email.hashCode());
    } catch (Exception e) {
      LOG.error("Échec envoi confirmation email : emailHash={}", email.hashCode(), e);
    }
  }

  /**
   * Indique si le service email est disponible (clé Resend configurée).
   *
   * @return {@code true} si {@code RESEND_API_KEY} est présent en configuration
   */
  public boolean estDisponible() {
    return configService.lireConfig("RESEND_API_KEY").isPresent();
  }

  /**
   * Signalement d'une salle manquante par un utilisateur.
   *
   * @param nom le nom de la salle signalée
   * @param ville la ville/code postal, peut être vide
   * @param urlSite l'URL du site ou de la billetterie, peut être vide
   * @param commentaire le commentaire libre, peut être vide
   * @param emailUtilisateur l'adresse email de l'utilisateur connecté
   */
  public record SignalementSalleManquante(
      String nom, String ville, String urlSite, String commentaire, String emailUtilisateur) {}

  /**
   * Envoie un signalement de salle manquante à l'admin, puis une confirmation à l'utilisateur.
   *
   * @param signalement le signalement à envoyer (voir {@link SignalementSalleManquante} )
   */
  public void envoyerSignalementSalleManquante(final SignalementSalleManquante signalement) {
    final String nom = signalement.nom();
    final String ville = signalement.ville();
    final String urlSite = signalement.urlSite();
    final String commentaire = signalement.commentaire();
    final String emailUtilisateur = signalement.emailUtilisateur();
    final Optional<EmailSender.ContexteEmail> ctx = emailSender.obtenirContexte();
    if (ctx.isEmpty()) {
      LOG.warn("RESEND_API_KEY absent — signalement salle manquante non envoyé");
      return;
    }

    final String corpsAdmin =
        EmailTemplates.signalementSalleManquante(
            nom, ville, urlSite, commentaire, emailUtilisateur, ctx.get().baseUrl());
    final String texteAdmin =
        "Salle manquante signalée par %s%nNom : %s%nVille : %s%nSite : %s%n%n%s"
            .formatted(emailUtilisateur, nom, ville, urlSite, commentaire);
    if (!envoyerSignalementAdmin(ctx.get(), emailUtilisateur, nom, corpsAdmin, texteAdmin)) {
      return;
    }

    final String corpsUser = EmailTemplates.confirmationSignalementSalle(nom, ctx.get().baseUrl());
    final String texteUser =
        "Votre signalement pour « %s » a été transmis à l'administrateur.".formatted(nom);
    envoyerConfirmationSignalement(ctx.get(), emailUtilisateur, corpsUser, texteUser);
  }

  // Extrait pour rester sous la limite de 50 lignes/méthode — comportement
  // inchangé, uniquement déplacé hors de envoyerSignalementSalleManquante.
  private boolean envoyerSignalementAdmin(
      final EmailSender.ContexteEmail ctx,
      final String emailUtilisateur,
      final String nom,
      final String corpsAdmin,
      final String texteAdmin) {
    try {
      emailSender.envoyer(
          ctx,
          ctx.expediteur(),
          emailUtilisateur,
          "[Strapontin] Salle manquante — " + nom,
          corpsAdmin,
          texteAdmin);
      LOG.info(
          "Signalement salle manquante envoyé par : emailHash={}", emailUtilisateur.hashCode());
      return true;
    } catch (Exception e) {
      LOG.error("Échec envoi signalement salle manquante", e);
      return false;
    }
  }

  private void envoyerConfirmationSignalement(
      final EmailSender.ContexteEmail ctx,
      final String emailUtilisateur,
      final String corpsUser,
      final String texteUser) {
    try {
      emailSender.envoyer(
          ctx,
          emailUtilisateur,
          ctx.expediteur(),
          "Votre signalement Strapontin a été transmis",
          corpsUser,
          texteUser);
      LOG.info(
          "Confirmation signalement salle envoyée : emailHash={}", emailUtilisateur.hashCode());
    } catch (Exception e) {
      LOG.error(
          "Échec envoi confirmation signalement salle : emailHash={}",
          emailUtilisateur.hashCode(),
          e);
    }
  }

  /**
   * Envoie un signalement d'utilisateur à l'adresse de contact.
   *
   * @param type le type ({@code bug}, {@code suggestion}, {@code question})
   * @param message le message
   * @param expediteurEmail l'email de l'expéditeur
   */
  public void envoyerSignalement(
      final String type, final String message, final String expediteurEmail) {
    final Optional<EmailSender.ContexteEmail> ctx = emailSender.obtenirContexte();
    if (ctx.isEmpty()) {
      LOG.warn("RESEND_API_KEY absent — signalement non envoyé");
      return;
    }

    final String typeLabel =
        switch (type) {
          case "bug" -> "Bug";
          case "suggestion" -> "Amélioration";
          case "question" -> "Question";
          default -> type;
        };

    final String corps =
        EmailTemplates.signalement(typeLabel, expediteurEmail, message, ctx.get().baseUrl());

    final String texte =
        "Signalement — %s%n%nEmail : %s%n%n%s".formatted(typeLabel, expediteurEmail, message);

    try {
      emailSender.envoyer(
          ctx.get(),
          ctx.get().expediteur(),
          expediteurEmail,
          "[Strapontin] Signalement — %s".formatted(typeLabel),
          corps,
          texte);
      LOG.info("Signalement envoyé : emailHash={}", expediteurEmail.hashCode());
    } catch (Exception e) {
      LOG.error("Échec envoi signalement", e);
    }
  }

  /**
   * Envoie un digest de nouveaux concerts à un utilisateur.
   *
   * @param email l'adresse email du destinataire
   * @param concerts la liste des concerts à notifier (doit être non vide)
   * @return {@code true} si l'email a été envoyé avec succès
   */
  public boolean envoyerDigestConcerts(final String email, final List<ConcertDigest> concerts) {
    if (concerts.isEmpty()) {
      return false;
    }

    final Optional<EmailSender.ContexteEmail> ctx = emailSender.obtenirContexte();
    if (ctx.isEmpty()) {
      LOG.warn("RESEND_API_KEY absent — digest concerts non envoyé");
      return false;
    }

    final int nb = concerts.size();
    final String sujet =
        nb == 1
            ? "1 nouveau concert correspond à vos préférences"
            : "%d nouveaux concerts correspondent à vos préférences".formatted(nb);

    final List<LigneConcert> lignes =
        concerts.stream()
            .map(
                c ->
                    new LigneConcert(
                        c.artiste(),
                        formaterDate(c.date()),
                        c.ville() != null ? c.salle() + " · " + c.ville() : c.salle(),
                        c.url()))
            .toList();
    final String corps = EmailTemplates.digestConcerts(sujet, lignes, ctx.get().baseUrl());

    try {
      emailSender.envoyer(ctx.get(), email, ctx.get().expediteur(), sujet, corps, sujet);
      LOG.info("Digest envoyé : emailHash={} ({} concert(s))", email.hashCode(), nb);
      return true;
    } catch (Exception e) {
      LOG.error("Échec envoi digest : emailHash={}", email.hashCode(), e);
      return false;
    }
  }

  /**
   * Notifie un utilisateur que son compte a été supprimé par un administrateur.
   *
   * @param email l'adresse de l'utilisateur supprimé
   */
  private static String formaterDate(final String dateIso) {
    final LocalDate date = LocalDate.parse(dateIso);
    return DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL)
        .withLocale(Locale.FRANCE)
        .format(date);
  }
}
