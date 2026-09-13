/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.email;

import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Emails transactionnels déclenchés par une action d'administration (suppression de compte,
 * changement de rôle, test de configuration).
 *
 * <p>Extrait d'{@link EmailService} (dont il partage l'infrastructure d'envoi {@link EmailSender})
 * pour rester sous la limite de 500 lignes par fichier — même principe de scission par
 * responsabilité qu'{@link EmailAlerteService}.
 *
 * @since 2.9.1
 * @author Kwaite
 */
@Service
public class EmailServiceAdmin {

  private static final Logger LOG = LoggerFactory.getLogger(EmailServiceAdmin.class);

  private final EmailSender emailSender;

  /**
   * @param emailSender infrastructure d'envoi Resend partagée
   */
  /* package-private : instancié uniquement par Spring (même package). */
  EmailServiceAdmin(final EmailSender emailSender) {
    this.emailSender = emailSender;
  }

  /**
   * Notifie un utilisateur de la suppression de son compte par un administrateur.
   *
   * @param email l'adresse de l'utilisateur supprimé
   */
  public void envoyerSuppressionCompte(final String email) {
    final Optional<EmailSender.ContexteEmail> ctx = emailSender.obtenirContexte();
    if (ctx.isEmpty()) {
      LOG.warn("RESEND_API_KEY absent — email suppression compte non envoyé");
      return;
    }

    final String corps = EmailTemplates.suppressionCompte(ctx.get().baseUrl());

    final String texte =
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
      LOG.info("Email suppression compte envoyé : emailHash={}", email.hashCode());
    } catch (Exception e) {
      LOG.error("Échec envoi suppression compte : emailHash={}", email.hashCode(), e);
    }
  }

  /**
   * Envoie un email de test pour vérifier la configuration Resend.
   *
   * @param emailAdmin l'adresse email de l'administrateur destinataire
   * @return {@code true} si l'email a été envoyé avec succès
   */
  public boolean envoyerEmailTest(final String emailAdmin) {
    final Optional<EmailSender.ContexteEmail> ctx = emailSender.obtenirContexte();
    if (ctx.isEmpty()) {
      LOG.warn("RESEND_API_KEY absent — email de test non envoyé");
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
      LOG.info("Email de test envoyé : emailHash={}", emailAdmin.hashCode());
      return true;
    } catch (Exception e) {
      LOG.error("Échec envoi email de test : emailHash={}", emailAdmin.hashCode(), e);
      return false;
    }
  }

  /**
   * Notifie un utilisateur qu'il a été promu au rôle administrateur.
   *
   * @param email l'adresse de l'utilisateur promu
   */
  public void envoyerPromotionAdmin(final String email) {
    final Optional<EmailSender.ContexteEmail> ctx = emailSender.obtenirContexte();
    if (ctx.isEmpty()) {
      LOG.warn("RESEND_API_KEY absent — email promotion admin non envoyé");
      return;
    }

    final String corps = EmailTemplates.promotionAdmin(ctx.get().baseUrl());

    final String texte =
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
      LOG.info("Email promotion admin envoyé : emailHash={}", email.hashCode());
    } catch (Exception e) {
      LOG.error("Échec envoi email promotion admin : emailHash={}", email.hashCode(), e);
    }
  }

  /**
   * Envoie un préavis de révocation du rôle administrateur (délai de 7 jours).
   *
   * @param email l'adresse de l'administrateur concerné
   * @param dateEffective la date effective de révocation formatée (ex. "22 juin 2026")
   */
  public void envoyerPreavisRevocationAdmin(final String email, final String dateEffective) {
    final Optional<EmailSender.ContexteEmail> ctx = emailSender.obtenirContexte();
    if (ctx.isEmpty()) {
      LOG.warn("RESEND_API_KEY absent — email préavis révocation admin non envoyé");
      return;
    }

    final String corps = EmailTemplates.preavisRevocationAdmin(dateEffective, ctx.get().baseUrl());

    final String texte =
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
      LOG.info("Email préavis révocation admin envoyé : emailHash={}", email.hashCode());
    } catch (Exception e) {
      LOG.error("Échec envoi préavis révocation admin : emailHash={}", email.hashCode(), e);
    }
  }
}
