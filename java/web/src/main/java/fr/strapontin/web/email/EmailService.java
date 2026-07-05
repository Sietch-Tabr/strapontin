/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.email;

import com.resend.Resend;
import com.resend.core.exception.ResendException;
import com.resend.services.emails.model.CreateEmailOptions;
import fr.strapontin.web.admin.LogAdminService;
import fr.strapontin.web.config.ConfigService;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.List;
import java.util.Locale;
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
  /// @param lienReset le lien de création de compte (valable 7 jours)
  public void envoyerInvitation(String email, String lienReset) {
    Optional<ContexteEmail> ctx = obtenirContexte();
    if (ctx.isEmpty()) {
      log.warn("RESEND_API_KEY absent — email invitation non envoyé");
      return;
    }

    String corps = EmailTemplates.invitation(lienReset, ctx.get().baseUrl());

    // #833 : corps texte brut corrigé à "7 jours" — copié depuis le gabarit de confirmation de
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
      envoyer(
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
      log.info("Email reset MDP envoyé : emailHash={}", email.hashCode());
    } catch (Exception e) {
      log.error("Échec envoi reset MDP : emailHash={}", email.hashCode(), e);
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
    Optional<ContexteEmail> ctx = obtenirContexte();
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
      envoyer(
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
      envoyer(
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
      envoyer(ctx.get(), email, ctx.get().expediteur(), sujet, corps, sujet);
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
      log.info("Email suppression compte envoyé : emailHash={}", email.hashCode());
    } catch (Exception e) {
      log.error("Échec envoi suppression compte : emailHash={}", email.hashCode(), e);
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
      log.info("Notification bounce envoyée : emailHash={}", emailBouncee.hashCode());
    } catch (Exception e) {
      log.error("Échec envoi notification bounce", e);
    }
  }

  /// Envoie une notification à l'admin quand un destinataire signale un email Strapontin comme
  /// spam (#832) — même traitement que le bounce, une plainte spam menaçant la réputation
  /// d'envoi du domaine au moins autant qu'un rejet définitif.
  ///
  /// @param emailPlaignant l'adresse email à l'origine de la plainte
  public void envoyerNotificationPlainteSpam(String emailPlaignant) {
    Optional<ContexteEmail> ctx = obtenirContexte();
    if (ctx.isEmpty()) {
      log.warn("RESEND_API_KEY absent — notification plainte spam non envoyée");
      return;
    }

    String corps = EmailTemplates.alertePlainteSpam(emailPlaignant, ctx.get().baseUrl());

    try {
      envoyer(
          ctx.get(),
          ctx.get().expediteur(),
          ctx.get().expediteur(),
          "Strapontin — plainte spam : " + emailPlaignant,
          corps,
          "Plainte spam signalée sur : "
              + emailPlaignant
              + ". Consultez l'administration : "
              + ctx.get().baseUrl()
              + "/admin");
      log.info("Notification plainte spam envoyée : emailHash={}", emailPlaignant.hashCode());
    } catch (Exception e) {
      log.error("Échec envoi notification plainte spam", e);
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
    Optional<ContexteEmail> ctx = obtenirContexte();
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
      envoyer(
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
    Optional<ContexteEmail> ctx = obtenirContexte();
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
      envoyer(
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
      log.info("Alerte worker envoyée : emailHash={}", email.hashCode());
    } catch (Exception e) {
      log.error("Échec envoi alerte worker", e);
    }
  }

  /// Envoie un digest des nouvelles entrées {@code logs_admin} de niveau erreur (#765).
  ///
  /// @param email   l'adresse email de l'administrateur
  /// @param erreurs les entrées d'erreur à inclure dans le digest (non vide)
  /// @return {@code true} si l'email a effectivement été envoyé, {@code false} sinon
  ///     (RESEND_API_KEY absent ou échec d'envoi) — dans ce cas le curseur ne doit pas avancer
  ///     côté appelant, pour ne perdre aucune erreur en cas de panne temporaire de Resend
  public boolean envoyerAlerteLogsAdmin(
      String email, List<LogAdminService.EntreeLogAdmin> erreurs) {
    Optional<ContexteEmail> ctx = obtenirContexte();
    if (ctx.isEmpty()) {
      log.warn("RESEND_API_KEY absent — alerte logs admin non envoyée");
      return false;
    }

    String corps = EmailTemplates.alerteLogsAdmin(erreurs, ctx.get().baseUrl());
    String sujet = "Strapontin — " + erreurs.size() + " nouvelle(s) erreur(s) admin";
    try {
      envoyer(
          ctx.get(),
          email,
          ctx.get().expediteur(),
          sujet,
          corps,
          erreurs.size()
              + " nouvelle(s) erreur(s) dans logs_admin. Consultez l'administration : "
              + ctx.get().baseUrl()
              + "/admin");
      log.info("Alerte logs admin envoyée : emailHash={} nb={}", email.hashCode(), erreurs.size());
      return true;
    } catch (Exception e) {
      log.error("Échec envoi alerte logs admin", e);
      return false;
    }
  }

  private static String formaterDate(String dateIso) {
    LocalDate date = LocalDate.parse(dateIso);
    return DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL)
        .withLocale(Locale.FRANCE)
        .format(date);
  }
}
