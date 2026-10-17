/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.email;

/**
 * Corps HTML des alertes de sécurité envoyées à l'utilisateur lui-même
 * — extrait d'{@link EmailTemplates} (helpers partagés).
 * @since 3.0.0
 * @author Kwaite
 */
final class EmailTemplatesSecurite {

  private EmailTemplatesSecurite() {}

  /**
   * Notification de verrouillage de compte par un administrateur.
   *
   * @param baseUrl l'URL publique de base de l'application
   * @return le HTML de l'email de notification
   */
  /* package-private : appelé par EmailServiceSecurite (même package). */
  static String compteVerrouille(final String baseUrl) {
    final String corps =
        """
        <p style="color: %s; margin: 0 0 24px">
          Un administrateur a verrouillé votre compte et révoqué toutes vos sessions actives.
          Vous ne pouvez plus vous connecter tant que le compte n'est pas déverrouillé.
        </p>
        <p style="color: %s; font-size: 13px; margin-top: 24px">
          Contactez l'administrateur pour en connaître la raison et demander le déverrouillage.
        </p>
        """
            .formatted(EmailTemplates.C_TEXTE, EmailTemplates.C_MUTE);
    return EmailTemplates.envelopper("Votre compte a été verrouillé", corps, baseUrl);
  }

  /**
   * Alerte de connexion depuis un appareil jamais vu.
   *
   * @param userAgent l'en-tête {@code User-Agent} du nouvel appareil
   * @param baseUrl l'URL publique de base de l'application
   * @return le HTML de l'email d'alerte
   */
  /* package-private : appelé par EmailServiceSecurite (même package). */
  static String alerteNouvelAppareil(final String userAgent, final String baseUrl) {
    final String appareil =
        userAgent == null || userAgent.isBlank() ? "appareil inconnu" : userAgent;
    final String corps =
        """
        <p style="color: %s; margin: 0 0 24px">
          Une connexion depuis un appareil jamais vu a été détectée sur votre compte :
        </p>
        <p style="color: %s; font-size: 13px; font-family: monospace; margin: 0 0 24px">%s</p>
        <p style="color: %s; font-size: 13px; margin-top: 24px">
          Si c'est vous, ignorez cet email. Sinon, changez immédiatement votre mot de passe
          depuis vos paramètres.
        </p>
        """
            .formatted(
                EmailTemplates.C_TEXTE,
                EmailTemplates.C_TEXTE,
                EmailTemplates.e(appareil),
                EmailTemplates.C_MUTE);
    return EmailTemplates.envelopper("Nouvelle connexion détectée", corps, baseUrl);
  }

  /**
   * Alerte d'échecs de connexion consécutifs sur un compte.
   *
   * @param baseUrl l'URL publique de base de l'application
   * @return le HTML de l'email d'alerte
   */
  /* package-private : appelé par EmailServiceSecurite (même package). */
  static String alerteEchecsConnexion(final String baseUrl) {
    final String corps =
        """
        <p style="color: %s; margin: 0 0 24px">
          Plusieurs tentatives de connexion infructueuses ont été détectées récemment sur
          votre compte.
        </p>
        <p style="color: %s; font-size: 13px; margin-top: 24px">
          Si ce n'est pas vous, changez votre mot de passe depuis vos paramètres et activez
          la double authentification si ce n'est pas déjà fait.
        </p>
        """
            .formatted(EmailTemplates.C_TEXTE, EmailTemplates.C_MUTE);
    return EmailTemplates.envelopper("Échecs de connexion répétés", corps, baseUrl);
  }

  /**
   * Communication d'un incident de sécurité à un utilisateur.
   * @param titre titre de la communication
   * @param message message de l'administrateur (texte brut)
   * @param changementMdpForce {@code true} si un nouveau mot de passe est exigé
   * @param baseUrl l'URL publique de base de l'application
   * @return le HTML de l'email
   */
  /* package-private : appelé par EmailServiceSecurite (même package). */
  static String communicationIncident(
      final String titre,
      final String message,
      final boolean changementMdpForce,
      final String baseUrl) {
    final String consigne =
        changementMdpForce
            ? "Par précaution, un nouveau mot de passe vous sera demandé à votre prochaine"
                + " connexion."
            : "";
    final String corps =
        """
        <p style="color: %s; margin: 0 0 24px; white-space: pre-line">%s</p>
        <p style="color: %s; font-size: 13px; margin-top: 24px">%s</p>
        """
            .formatted(
                EmailTemplates.C_TEXTE,
                EmailTemplates.e(message),
                EmailTemplates.C_MUTE,
                EmailTemplates.e(consigne));
    return EmailTemplates.envelopper(titre, corps, baseUrl);
  }

  /**
   * Prévient un utilisateur que ses appareils push ont tous été retirés après le renouvellement
   * des clés de l'application, et lui dit comment les réactiver.
   * @param motif raison du renouvellement, saisie par l'administrateur
   * @param baseUrl adresse publique de l'application
   * @return le corps HTML de l'email
   */
  static String pushReinitialise(final String motif, final String baseUrl) {
    final String corps =
        """
        <p style="color: %s; margin: 0 0 16px">Les notifications push de votre compte ont été
        désactivées sur tous vos appareils : les clés de l'application ont été renouvelées.</p>
        <p style="color: %s; margin: 0 0 16px">Motif : %s</p>
        <p style="color: %s; margin: 0 0 16px">Pour les réactiver, ouvrez Strapontin sur chacun de
        vos appareils, puis Paramètres, Notifications, « Activer sur cet appareil ».</p>
        """
            .formatted(
                EmailTemplates.C_TEXTE,
                EmailTemplates.C_TEXTE,
                EmailTemplates.e(motif),
                EmailTemplates.C_TEXTE);
    return EmailTemplates.envelopper("Vos notifications push sont à réactiver", corps, baseUrl);
  }
}
