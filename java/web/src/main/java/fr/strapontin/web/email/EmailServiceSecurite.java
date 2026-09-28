/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.email;

import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Alertes de sécurité envoyées à l'utilisateur lui-même — extrait d'{@link
 * EmailService} (même pattern d'envoi, {@link EmailSender}).
 * @since 3.0.0
 * @author Kwaite
 */
@Service
public class EmailServiceSecurite {

  private static final Logger LOG = LoggerFactory.getLogger(EmailServiceSecurite.class);

  private final EmailSender emailSender;

  /* package-private : instancié uniquement par Spring (même package). */
  EmailServiceSecurite(final EmailSender emailSender) {
    this.emailSender = emailSender;
  }

  /**
   * Notifie un utilisateur que son compte a été verrouillé par un admin.
   *
   * @param email l'adresse de l'utilisateur concerné
   */
  public void envoyerCompteVerrouille(final String email) {
    final Optional<EmailSender.ContexteEmail> ctx = emailSender.obtenirContexte();
    if (ctx.isEmpty()) {
      LOG.warn("RESEND_API_KEY absent — email verrouillage compte non envoyé");
      return;
    }

    final String corps = EmailTemplatesSecurite.compteVerrouille(ctx.get().baseUrl());

    final String texte =
        """
        Votre compte a été verrouillé

        Un administrateur a verrouillé votre compte Strapontin et révoqué toutes vos
        sessions actives. Contactez l'administrateur pour en connaître la raison et
        demander le déverrouillage.
        """;

    try {
      emailSender.envoyer(
          ctx.get(),
          email,
          ctx.get().expediteur(),
          "Votre compte Strapontin a été verrouillé",
          corps,
          texte);
      LOG.info("Email verrouillage compte envoyé : emailHash={}", email.hashCode());
    } catch (Exception e) {
      LOG.error("Échec envoi verrouillage compte : emailHash={}", email.hashCode(), e);
    }
  }

  /**
   * Alerte un utilisateur qu'une connexion depuis un appareil jamais vu a
   * été détectée sur son compte.
   *
   * @param email l'adresse de l'utilisateur concerné
   * @param userAgent l'en-tête {@code User-Agent} du nouvel appareil
   */
  public void envoyerAlerteNouvelAppareil(final String email, final String userAgent) {
    final Optional<EmailSender.ContexteEmail> ctx = emailSender.obtenirContexte();
    if (ctx.isEmpty()) {
      LOG.warn("RESEND_API_KEY absent — alerte nouvel appareil non envoyée");
      return;
    }

    final String corps =
        EmailTemplatesSecurite.alerteNouvelAppareil(userAgent, ctx.get().baseUrl());

    final String texte =
        """
        Nouvelle connexion détectée

        Une connexion depuis un appareil jamais vu a été détectée sur votre compte
        Strapontin (%s). Si c'est vous, ignorez cet email. Sinon, changez immédiatement
        votre mot de passe depuis vos paramètres.
        """
            .formatted(userAgent == null || userAgent.isBlank() ? "appareil inconnu" : userAgent);

    try {
      emailSender.envoyer(
          ctx.get(),
          email,
          ctx.get().expediteur(),
          "Nouvelle connexion sur votre compte Strapontin",
          corps,
          texte);
      LOG.info("Alerte nouvel appareil envoyée : emailHash={}", email.hashCode());
    } catch (Exception e) {
      LOG.error("Échec envoi alerte nouvel appareil : emailHash={}", email.hashCode(), e);
    }
  }

  /**
   * Alerte un utilisateur que plusieurs échecs de connexion consécutifs ont
   * été détectés sur son compte.
   *
   * @param email l'adresse de l'utilisateur concerné
   */
  public void envoyerAlerteEchecsConnexion(final String email) {
    final Optional<EmailSender.ContexteEmail> ctx = emailSender.obtenirContexte();
    if (ctx.isEmpty()) {
      LOG.warn("RESEND_API_KEY absent — alerte échecs de connexion non envoyée");
      return;
    }

    final String corps = EmailTemplatesSecurite.alerteEchecsConnexion(ctx.get().baseUrl());

    final String texte =
        """
        Plusieurs échecs de connexion détectés

        Plusieurs tentatives de connexion infructueuses ont été détectées sur votre
        compte Strapontin. Si ce n'est pas vous, changez votre mot de passe depuis vos
        paramètres et activez la double authentification si ce n'est pas déjà fait.
        """;

    try {
      emailSender.envoyer(
          ctx.get(),
          email,
          ctx.get().expediteur(),
          "Échecs de connexion répétés sur votre compte Strapontin",
          corps,
          texte);
      LOG.info("Alerte échecs de connexion envoyée : emailHash={}", email.hashCode());
    } catch (Exception e) {
      LOG.error("Échec envoi alerte échecs de connexion : emailHash={}", email.hashCode(), e);
    }
  }

  /**
   * Nombre d'emails encore envoyables avant blocage par le quota Resend.
   * @return envois restants (jour et mois confondus)
   */
  public int envoisEmailRestants() {
    return emailSender.envoisRestants();
  }

  /**
   * Communique un incident de sécurité à chaque destinataire, un email par
   * compte (jamais un envoi groupé exposant les adresses des autres).
   *
   * @param destinataires adresses des comptes à prévenir
   * @param titre titre de la communication
   * @param message message rédigé par l'administrateur
   * @param changementMdpForce {@code true} si un nouveau mot de passe est exigé
   * @return nombre d'emails effectivement envoyés
   */
  public int envoyerCommunicationIncident(
      final List<String> destinataires,
      final String titre,
      final String message,
      final boolean changementMdpForce) {
    final Optional<EmailSender.ContexteEmail> ctx = emailSender.obtenirContexte();
    if (ctx.isEmpty()) {
      LOG.warn("RESEND_API_KEY absent — communication d'incident non envoyée par email");
      return 0;
    }
    final String corps =
        EmailTemplatesSecurite.communicationIncident(
            titre, message, changementMdpForce, ctx.get().baseUrl());
    int envoyes = 0;
    for (final String destinataire : destinataires) {
      try {
        if (emailSender.envoyer(
            ctx.get(), destinataire, ctx.get().expediteur(), titre, corps, message)) {
          envoyes++;
        }
      } catch (Exception e) {
        LOG.error(
            "Échec envoi communication d'incident : emailHash={}", destinataire.hashCode(), e);
      }
    }
    LOG.info("communication d'incident envoyée par email : {}/{}", envoyes, destinataires.size());
    return envoyes;
  }
}
