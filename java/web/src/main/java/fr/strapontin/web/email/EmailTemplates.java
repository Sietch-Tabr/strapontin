/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.email;

import fr.strapontin.core.UtilitairesChaine;
import java.util.List;

/**
 * Génération des corps HTML de tous les emails transactionnels
 * Strapontin — helpers factorisés : {@link #envelopper},
 * {@link #enveloppErreur}, {@link #bouton}, {@link #e}.
 * @since 2.2.0
 * @author Kwaite
 */
final class EmailTemplates {

  private static final String C_NOIR = "#111";
  /* package-private : couleur réutilisée par EmailTemplatesAlertes (idem). */
  static final String C_TEXTE = "#555";
  private static final String C_MUTE = "#aaa";
  /* package-private : couleur réutilisée par EmailTemplatesAlertes (idem). */
  static final String C_ERREUR = "#c00";
  private static final String C_BORDURE = "#e5e5e5";

  private EmailTemplates() {}

  // ---- Emails transactionnels ----

  /**
   * Invitation d'un nouvel utilisateur.
   *
   * @param lienReset le lien de création de compte (valable 7 jours)
   * @param baseUrl l'URL publique de base de l'application
   * @return le HTML de l'email d'invitation
   */
  /* package-private : appelé par EmailService(Admin) (même package). */
  static String invitation(final String lienReset, final String baseUrl) {
    final String corps =
        """
        <p style="color: %s; margin: 0 0 24px">
          Votre compte a été créé. Cliquez sur le bouton ci-dessous pour choisir
          votre identifiant et votre mot de passe :
        </p>
        %s
        <p style="color: %s; font-size: 13px; margin: 16px 0 0">
          Ce lien est valable 7 jours.
        </p>
        """
            .formatted(C_TEXTE, bouton(lienReset, "Créer mon compte →"), C_MUTE);
    return envelopper("Bienvenue sur Strapontin", corps, baseUrl);
  }

  /**
   * Réinitialisation du mot de passe.
   *
   * @param lienReset le lien de réinitialisation (valable 1 heure)
   * @param baseUrl l'URL publique de base de l'application
   * @return le HTML de l'email de réinitialisation
   */
  /* package-private : appelé par EmailService(Admin) (même package). */
  static String resetMotDePasse(final String lienReset, final String baseUrl) {
    final String corps =
        """
        <p style="color: %s; margin: 0 0 24px">
          Vous avez demandé la réinitialisation de votre mot de passe. Cliquez sur
          le bouton ci-dessous. Ce lien est valable 1 heure.
        </p>
        %s
        <p style="color: %s; font-size: 13px; margin-top: 24px">
          Si vous n'êtes pas à l'origine de cette demande, ignorez cet email.
        </p>
        """
            .formatted(C_TEXTE, bouton(lienReset, "Réinitialiser mon mot de passe →"), C_MUTE);
    return envelopper("Réinitialisation du mot de passe", corps, baseUrl);
  }

  /**
   * Notification de réinitialisation de la double authentification par un
   * administrateur.
   *
   * @param baseUrl l'URL publique de base de l'application
   * @return le HTML de l'email de notification
   */
  /* package-private : appelé par EmailService(Admin) (même package). */
  static String reinitialisation2fa(final String baseUrl) {
    final String corps =
        """
        <p style="color: %s; margin: 0 0 24px">
          La double authentification (2FA) de votre compte a été réinitialisée par un
          administrateur. Vous pouvez vous reconnecter sans code, puis la reconfigurer
          depuis vos paramètres.
        </p>
        %s
        <p style="color: %s; font-size: 13px; margin-top: 24px">
          Si vous n'êtes pas à l'origine de cette action, contactez immédiatement
          l'administrateur : votre compte pourrait être compromis.
        </p>
        """
            .formatted(
                C_TEXTE, bouton(baseUrl + "/parametres/totp", "Reconfigurer la 2FA →"), C_MUTE);
    return envelopper("Votre double authentification a été réinitialisée", corps, baseUrl);
  }

  /**
   * Confirmation de changement d'adresse email.
   *
   * @param lien le lien de confirmation (valable 24 heures)
   * @param baseUrl l'URL publique de base de l'application
   * @return le HTML de l'email de confirmation
   */
  /* package-private : appelé par EmailService(Admin) (même package). */
  static String confirmationEmail(final String lien, final String baseUrl) {
    final String corps =
        """
        <p style="color: %s; margin: 0 0 24px">
          Cliquez sur le bouton ci-dessous pour valider votre nouvelle adresse.
          Ce lien est valable 24 heures.
        </p>
        %s
        <p style="color: %s; font-size: 13px; margin-top: 24px">
          Si vous n'êtes pas à l'origine de cette demande, ignorez cet email.
          Votre adresse actuelle reste inchangée.
        </p>
        """
            .formatted(C_TEXTE, bouton(lien, "Confirmer ma nouvelle adresse →"), C_MUTE);
    return envelopper("Confirmez votre nouvelle adresse email", corps, baseUrl);
  }

  /**
   * Signalement utilisateur transmis à l'adresse de contact.
   * @param typeLabel libellé du type ("Bug", "Amélioration"...)
   * @param expediteurEmail adresse email de l'expéditeur
   * @param message corps du signalement
   * @param baseUrl URL publique de base
   * @return le HTML de l'email
   */
  /* package-private : appelé par EmailService(Admin) (même package). */
  static String signalement(
      final String typeLabel,
      final String expediteurEmail,
      final String message,
      final String baseUrl) {
    final String corps =
        """
        <table style="border-collapse: collapse; width: 100%%; margin-bottom: 24px">
          <tr>
            <td style="padding: 8px 0; color: #888; font-size: 14px; width: 130px">Email</td>
            <td style="padding: 8px 0; font-size: 14px; color: %s">%s</td>
          </tr>
        </table>
        <p style="color: %s; font-size: 14px; white-space: pre-wrap; border-left: 3px solid %s; padding-left: 12px; margin: 0">%s</p>
        """
            .formatted(C_NOIR, e(expediteurEmail), C_TEXTE, C_BORDURE, e(message));
    return envelopper("Signalement — " + typeLabel, corps, baseUrl);
  }

  /**
   * Digest de nouveaux concerts.
   *
   * @param sujet le sujet de l'email (aussi utilisé comme titre H2)
   * @param lignes la liste des concerts à afficher
   * @param baseUrl l'URL publique de base de l'application
   * @return le HTML du digest
   */
  /* package-private : appelé par EmailService(Admin) (même package). */
  static String digestConcerts(
      final String sujet, final List<LigneConcert> lignes, final String baseUrl) {
    final StringBuilder lignesHtml = new StringBuilder();
    String artistePrecedent = null;
    for (final LigneConcert c : lignes) {
      final String urlBillets =
          c.url() != null
              ? " — <a href=\"%s\" style=\"color: %s; font-size: 13px\">Billets →</a>"
                  .formatted(e(c.url()), C_NOIR)
              : "";
      // Rupture visuelle plus marquée qu'entre deux concerts d'un même artiste
      // — sans elle, un digest couvrant plusieurs artistes se lit comme une
      // liste continue.
      final String stylecel =
          artistePrecedent != null && !artistePrecedent.equals(c.artiste())
              ? "padding: 16px 0 10px; border-top: 2px solid #ddd; vertical-align: top"
              : "padding: 10px 0; border-bottom: 1px solid #f0f0f0; vertical-align: top";
      lignesHtml.append(
          """
          <tr>
            <td style="%s">
              <div style="font-weight: 600; color: %s; font-size: 14px">%s</div>
              <div style="color: #666; font-size: 13px; margin-top: 2px"><span>%s</span>%s</div>
              <div style="color: #999; font-size: 12px; margin-top: 2px">%s</div>
            </td>
          </tr>
          """
              .formatted(
                  stylecel, C_NOIR, e(c.artiste()), e(c.dateFormatee()), urlBillets, e(c.lieu())));
      artistePrecedent = c.artiste();
    }
    final String corps =
        """
        <p style="color: %s; font-size: 14px; margin: 0 0 24px">Voici les nouveaux concerts ajoutés :</p>
        <table style="border-collapse: collapse; width: 100%%">%s</table>
        <p style="margin-top: 24px">%s</p>
        """
            .formatted(C_TEXTE, lignesHtml, bouton(baseUrl + "/", "Voir mes spectacles →"));
    return envelopper(sujet, corps, baseUrl);
  }

  /**
   * Notification de suppression de compte par un administrateur.
   *
   * @param baseUrl l'URL publique de base de l'application
   * @return le HTML de l'email de notification
   */
  /* package-private : appelé par EmailService(Admin) (même package). */
  static String suppressionCompte(final String baseUrl) {
    final String corps =
        """
        <p style="color: %s; margin: 0 0 24px">
          Votre compte Strapontin a été supprimé par un administrateur.
          Vos données ont été effacées.
        </p>
        <p style="color: %s; font-size: 13px; margin: 0">
          Si vous pensez qu'il s'agit d'une erreur, contactez l'administrateur.
        </p>
        """
            .formatted(C_TEXTE, C_MUTE);
    return envelopper("Votre compte Strapontin a été supprimé", corps, baseUrl);
  }

  /**
   * Signalement d'une salle manquante transmis à l'admin,
   * paramètres auto-descriptifs.
   * @return le HTML de l'email
   */
  /* package-private : appelé par EmailService(Admin) (même package). */
  static String signalementSalleManquante(
      final String nom,
      final String ville,
      final String urlSite,
      final String commentaire,
      final String emailUtilisateur,
      final String baseUrl) {
    final String blocCommentaire =
        (commentaire != null && !commentaire.isBlank())
            ? """
            <p style="color: %s; font-size: 14px; white-space: pre-wrap;\
             border-left: 3px solid %s; padding-left: 12px; margin: 16px 0 0">%s</p>
            """
                .formatted(C_TEXTE, C_BORDURE, e(commentaire))
            : "";
    final String corps =
        """
        <table style="border-collapse: collapse; width: 100%%; margin-bottom: 8px">
          <tr>
            <td style="padding: 8px 0; color: #888; font-size: 14px; width: 140px">Utilisateur</td>
            <td style="padding: 8px 0; font-size: 14px; color: %s">%s</td>
          </tr>
          <tr>
            <td style="padding: 8px 0; color: #888; font-size: 14px">Nom de la salle</td>
            <td style="padding: 8px 0; font-size: 14px; color: %s"><strong>%s</strong></td>
          </tr>
          <tr>
            <td style="padding: 8px 0; color: #888; font-size: 14px">Ville / CP</td>
            <td style="padding: 8px 0; font-size: 14px; color: %s">%s</td>
          </tr>
          <tr>
            <td style="padding: 8px 0; color: #888; font-size: 14px">Site / billetterie</td>
            <td style="padding: 8px 0; font-size: 14px; color: %s">%s</td>
          </tr>
        </table>
        %s
        """
            .formatted(
                C_NOIR,
                e(emailUtilisateur),
                C_NOIR,
                e(nom),
                C_TEXTE,
                ville != null && !ville.isBlank() ? e(ville) : "—",
                C_TEXTE,
                urlSite != null && !urlSite.isBlank() ? e(urlSite) : "—",
                blocCommentaire);
    return envelopper("Salle manquante signalée", corps, baseUrl);
  }

  /**
   * Confirmation de signalement de salle manquante envoyée à l'utilisateur.
   *
   * @param nom le nom de la salle signalée
   * @param baseUrl l'URL publique de base de l'application
   * @return le HTML de l'email de confirmation
   */
  /* package-private : appelé par EmailService(Admin) (même package). */
  static String confirmationSignalementSalle(final String nom, final String baseUrl) {
    final String corps =
        """
        <p style="color: %s; margin: 0 0 16px">
          Votre signalement pour <strong>%s</strong> a été transmis à l'administrateur.
        </p>
        <p style="color: %s; font-size: 13px; margin: 0">
          L'administrateur pourra vous répondre directement par email si besoin.
        </p>
        """
            .formatted(C_TEXTE, e(nom), C_MUTE);
    return envelopper("Votre signalement a été transmis", corps, baseUrl);
  }

  /**
   * Notification de promotion au rôle administrateur.
   *
   * @param baseUrl l'URL publique de base de l'application
   * @return le HTML de l'email de promotion
   */
  /* package-private : appelé par EmailService(Admin) (même package). */
  static String promotionAdmin(final String baseUrl) {
    final String corps =
        """
        <p style="color: %s; margin: 0 0 24px">
          Votre compte Strapontin a été promu au rôle administrateur.
          Vous avez désormais accès au panneau d'administration.
        </p>
        %s
        """
            .formatted(C_TEXTE, bouton(baseUrl + "/admin", "Accéder à l'administration →"));
    return envelopper("Vous êtes maintenant administrateur Strapontin", corps, baseUrl);
  }

  /**
   * Préavis de révocation du rôle administrateur (délai de 7 jours).
   *
   * @param dateEffective la date effective de révocation formatée (ex. "22 juin
   *     2026")
   * @param baseUrl l'URL publique de base de l'application
   * @return le HTML de l'email de préavis
   */
  /* package-private : appelé par EmailService(Admin) (même package). */
  static String preavisRevocationAdmin(final String dateEffective, final String baseUrl) {
    final String corps =
        """
        <p style="color: %s; margin: 0 0 24px">
          Votre accès administrateur Strapontin sera supprimé le <strong>%s</strong>.
          Après cette date, vous ne pourrez plus accéder au panneau d'administration.
        </p>
        <p style="color: %s; font-size: 13px; margin: 0">
          Si vous pensez qu'il s'agit d'une erreur, contactez un autre administrateur.
        </p>
        """
            .formatted(C_TEXTE, e(dateEffective), C_MUTE);
    return enveloppErreur("Préavis de révocation admin", corps, baseUrl);
  }

  /**
   * Email de test de configuration Resend.
   *
   * @param baseUrl l'URL publique de base de l'application
   * @return le HTML de l'email de test
   */
  /* package-private : appelé par EmailService(Admin) (même package). */
  static String emailTest(final String baseUrl) {
    final String corps =
        """
        <p style="color: %s; margin: 0 0 16px">
          Si vous recevez cet email, la configuration Resend est correcte.
        </p>
        <p style="color: %s; font-size: 13px; margin: 0">
          Envoyé depuis l'administration Strapontin.
        </p>
        """
            .formatted(C_TEXTE, C_MUTE);
    return envelopper("Test de configuration email", corps, baseUrl);
  }

  // ---- Helpers privés ----

  private static String envelopper(final String titre, final String corps, final String baseUrl) {
    return enveloppAvecCouleur(C_NOIR, titre, corps, baseUrl);
  }

  /* package-private : appelé par EmailTemplates/EmailTemplatesAlertes. */
  static String enveloppErreur(final String titre, final String corps, final String baseUrl) {
    return enveloppAvecCouleur(C_ERREUR, titre, corps, baseUrl);
  }

  private static String enveloppAvecCouleur(
      final String couleur, final String titre, final String corps, final String baseUrl) {
    return """
    <!doctype html>
    <html lang="fr">
    <head>
      <meta charset="utf-8" />
      <meta name="viewport" content="width=device-width, initial-scale=1" />
    </head>
    <body style="font-family: system-ui, sans-serif; background: #f9f9f9; margin: 0; padding: 32px 16px">
      <div style="max-width: 520px; margin: 0 auto; background: #fff; border-radius: 8px; padding: 32px; border: 1px solid %s">
        <h2 style="font-size: 20px; font-weight: 600; color: %s; margin: 0 0 8px">%s</h2>
        %s
      </div>
      <p style="text-align: center; color: %s; font-size: 12px; margin-top: 24px">
        Strapontin — <a href="%s/parametres" style="color: %s">Gérer mes préférences</a>
      </p>
    </body>
    </html>
    """
        .formatted(C_BORDURE, couleur, e(titre), corps, C_MUTE, e(baseUrl), C_MUTE);
  }

  /* package-private : appelé par EmailTemplates/EmailTemplatesAlertes. */
  static String bouton(final String href, final String texte) {
    return """
    <a href="%s" style="display: inline-block; background: %s; color: #fff; text-decoration: none; padding: 10px 20px; border-radius: 6px; font-size: 14px; font-weight: 500">%s</a>
    """
        .formatted(e(href), C_NOIR, e(texte));
  }

  /**
   * Échappe les caractères HTML spéciaux d'une valeur variable.
   *
   * @param valeur la valeur à échapper (null retourne une chaîne vide)
   * @return la valeur échappée, sûre pour insertion dans un attribut ou un nœud
   *     HTML
   */
  // Nom volontairement court : appelé des dizaines de fois dans des lignes de
  // formatage HTML déjà denses (voir les gabarits ci-dessus) — un nom plus long
  // nuirait à leur lisibilité.
  @SuppressWarnings("PMD.ShortMethodName")
  /* package-private : appelé par EmailTemplates/EmailTemplatesAlertes. */
  static String e(final Object valeur) {
    return UtilitairesChaine.echapperHtml(valeur);
  }
}
