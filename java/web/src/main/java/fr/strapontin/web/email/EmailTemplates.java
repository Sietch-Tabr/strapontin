/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.email;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/// Génération des corps HTML de tous les emails transactionnels Strapontin.
///
/// <p>Chaque méthode publique retourne un document HTML complet, prêt à être
/// transmis à l'API Resend. Le contenu variable est systématiquement passé
/// par {@link #e(Object)} pour prévenir toute injection HTML.
///
/// <p>Structure commune factorisée :
/// <ul>
///   <li>{@link #envelopper} — cadre centré blanc + footer "Gérer mes préférences"</li>
///   <li>{@link #enveloppErreur} — idem avec titre en rouge (#c00)</li>
///   <li>{@link #bouton} — CTA noir réutilisé dans la majorité des emails</li>
///   <li>{@link #e} — échappement HTML des valeurs variables</li>
/// </ul>
///
/// @since 2.2.0
/// @author Kwaite
final class EmailTemplates {

  private EmailTemplates() {}

  // ---- Emails transactionnels ----

  /// Invitation d'un nouvel utilisateur.
  ///
  /// @param identifiant l'identifiant temporaire attribué
  /// @param lienReset   le lien de création de mot de passe (valable 24 heures)
  /// @param baseUrl     l'URL publique de base de l'application
  /// @return le HTML de l'email d'invitation
  static String invitation(String identifiant, String lienReset, String baseUrl) {
    String corps =
        """
        <p style="color: #555; margin: 0 0 24px">
          Votre compte a été créé. Commencez par choisir votre mot de passe :
        </p>
        %s
        <p style="color: #aaa; font-size: 13px; margin: 16px 0 0">
          Ce lien est valable 24 heures. Une fois votre mot de passe créé,
          connectez-vous avec votre identifiant temporaire :
          <code style="font-family: monospace; background: #f5f5f5; padding: 2px 6px; border-radius: 4px">%s</code>
        </p>
        <p style="color: #aaa; font-size: 13px; margin: 8px 0 0">
          Il vous sera demandé de choisir un identifiant définitif à la première connexion.
        </p>
        """
            .formatted(bouton(lienReset, "Créer mon mot de passe →"), e(identifiant));
    return envelopper("Bienvenue sur Strapontin", corps, baseUrl);
  }

  /// Réinitialisation du mot de passe.
  ///
  /// @param lienReset le lien de réinitialisation (valable 1 heure)
  /// @param baseUrl   l'URL publique de base de l'application
  /// @return le HTML de l'email de réinitialisation
  static String resetMotDePasse(String lienReset, String baseUrl) {
    String corps =
        """
        <p style="color: #555; margin: 0 0 24px">
          Vous avez demandé la réinitialisation de votre mot de passe. Cliquez sur
          le bouton ci-dessous. Ce lien est valable 1 heure.
        </p>
        %s
        <p style="color: #aaa; font-size: 13px; margin-top: 24px">
          Si vous n'êtes pas à l'origine de cette demande, ignorez cet email.
        </p>
        """
            .formatted(bouton(lienReset, "Réinitialiser mon mot de passe →"));
    return envelopper("Réinitialisation du mot de passe", corps, baseUrl);
  }

  /// Confirmation de changement d'adresse email.
  ///
  /// @param lien    le lien de confirmation (valable 24 heures)
  /// @param baseUrl l'URL publique de base de l'application
  /// @return le HTML de l'email de confirmation
  static String confirmationEmail(String lien, String baseUrl) {
    String corps =
        """
        <p style="color: #555; margin: 0 0 24px">
          Cliquez sur le bouton ci-dessous pour valider votre nouvelle adresse.
          Ce lien est valable 24 heures.
        </p>
        %s
        <p style="color: #aaa; font-size: 13px; margin-top: 24px">
          Si vous n'êtes pas à l'origine de cette demande, ignorez cet email.
          Votre adresse actuelle reste inchangée.
        </p>
        """
            .formatted(bouton(lien, "Confirmer ma nouvelle adresse →"));
    return envelopper("Confirmez votre nouvelle adresse email", corps, baseUrl);
  }

  /// Signalement utilisateur transmis à l'adresse de contact.
  ///
  /// @param typeLabel       le libellé du type ("Bug", "Amélioration", "Question")
  /// @param expediteurEmail l'adresse email de l'expéditeur
  /// @param message         le corps du signalement
  /// @param baseUrl         l'URL publique de base de l'application
  /// @return le HTML de l'email de signalement
  static String signalement(
      String typeLabel, String expediteurEmail, String message, String baseUrl) {
    String corps =
        """
        <table style="border-collapse: collapse; width: 100%%; margin-bottom: 24px">
          <tr>
            <td style="padding: 8px 0; color: #888; font-size: 14px; width: 130px">Email</td>
            <td style="padding: 8px 0; font-size: 14px; color: #111">%s</td>
          </tr>
        </table>
        <p style="color: #555; font-size: 14px; white-space: pre-wrap; border-left: 3px solid #e5e5e5; padding-left: 12px; margin: 0">%s</p>
        """
            .formatted(e(expediteurEmail), e(message));
    return envelopper("Signalement — " + e(typeLabel), corps, baseUrl);
  }

  /// Digest de nouveaux concerts.
  ///
  /// <p>Chaque entrée de {@code lignes} doit contenir les clés :
  /// {@code artiste}, {@code dateFormatee}, {@code lieu} (toutes non nulles),
  /// et optionnellement {@code url} (null → pas de lien "Billets →").
  ///
  /// @param sujet   le sujet de l'email (aussi utilisé comme titre H2)
  /// @param lignes  la liste des concerts à afficher
  /// @param baseUrl l'URL publique de base de l'application
  /// @return le HTML du digest
  static String digestConcerts(String sujet, List<Map<String, Object>> lignes, String baseUrl) {
    String lignesHtml =
        lignes.stream()
            .map(
                c -> {
                  String urlBillets =
                      c.get("url") != null
                          ? " — <a href=\"%s\" style=\"color: #111; font-size: 13px\">Billets →</a>"
                              .formatted(e(c.get("url")))
                          : "";
                  return """
                  <tr>
                    <td style="padding: 10px 0; border-bottom: 1px solid #f0f0f0; vertical-align: top">
                      <div style="font-weight: 600; color: #111; font-size: 14px">%s</div>
                      <div style="color: #666; font-size: 13px; margin-top: 2px"><span>%s</span>%s</div>
                      <div style="color: #999; font-size: 12px; margin-top: 2px">%s</div>
                    </td>
                  </tr>
                  """
                      .formatted(
                          e(c.get("artiste")),
                          e(c.get("dateFormatee")),
                          urlBillets,
                          e(c.get("lieu")));
                })
            .collect(Collectors.joining());
    String corps =
        """
        <p style="color: #555; font-size: 14px; margin: 0 0 24px">Voici les nouveaux concerts ajoutés :</p>
        <table style="border-collapse: collapse; width: 100%%">%s</table>
        <p style="margin-top: 24px">%s</p>
        """
            .formatted(lignesHtml, bouton(baseUrl + "/", "Voir mes spectacles →"));
    return envelopper(e(sujet), corps, baseUrl);
  }

  /// Alerte d'échec du backup nocturne.
  ///
  /// @param horodatage l'horodatage YYYYMMDD_HHMMSS, ou chaîne vide si inconnu
  /// @param raison     la commande ayant échoué, ou chaîne vide
  /// @param baseUrl    l'URL publique de base de l'application
  /// @return le HTML de l'alerte backup
  static String alerteBackup(String horodatage, String raison, String baseUrl) {
    String horodatageHtml =
        (horodatage != null && !horodatage.isBlank())
            ? "Démarré le : " + e(horodatage)
            : "Horodatage indisponible.";
    String raisonHtml =
        (raison != null && !raison.isBlank())
            ? """
            <p style="color: #333; font-size: 13px; font-family: monospace; background: #f5f5f5; \
            border-left: 3px solid #c00; padding: 8px 12px; margin: 0 0 16px; word-break: break-all">%s</p>
            """
                .formatted(e(raison))
            : "";
    String corps =
        """
        <p style="color: #555; font-size: 14px; margin: 0 0 8px">%s</p>
        %s
        <p style="color: #555; font-size: 14px; margin: 0 0 24px">
          Le backup local nocturne (dump PostgreSQL + .env + logs) n'a pas pu être
          complété. Vérifiez l'espace disque et les logs systemd sur le serveur.
        </p>
        <p style="margin-top: 24px">%s</p>
        """
            .formatted(
                horodatageHtml,
                raisonHtml,
                bouton(baseUrl + "/admin", "Aller à l'administration →"));
    return enveloppErreur("Échec du backup nocturne", corps, baseUrl);
  }

  /// Alerte de bounce email (rejet définitif par le serveur destinataire).
  ///
  /// @param emailBouncee l'adresse email ayant produit le bounce
  /// @param baseUrl      l'URL publique de base de l'application
  /// @return le HTML de l'alerte bounce
  static String alerteBounce(String emailBouncee, String baseUrl) {
    String corps =
        """
        <p style="color: #555; font-size: 14px; margin: 0 0 16px">
          Un email envoyé par Strapontin a été rejeté définitivement par le serveur destinataire.
        </p>
        <p style="color: #555; font-size: 14px; margin: 0 0 24px">
          Adresse concernée : <strong>%s</strong>
        </p>
        <p style="color: #555; font-size: 14px; margin: 0 0 24px">
          Vérifiez l'adresse email de cet utilisateur depuis la page d'administration.
        </p>
        <p style="margin-top: 24px">%s</p>
        """
            .formatted(e(emailBouncee), bouton(baseUrl + "/admin", "Aller à l'administration →"));
    return enveloppErreur("Bounce email", corps, baseUrl);
  }

  /// Alerte de worker inactif (aucun job depuis plus de 3 heures).
  ///
  /// @param detail  description de la dernière activité (jamais null)
  /// @param baseUrl l'URL publique de base de l'application
  /// @return le HTML de l'alerte worker
  static String alerteWorker(String detail, String baseUrl) {
    String corps =
        """
        <p style="color: #555; font-size: 14px; margin: 0 0 16px">%s</p>
        <p style="color: #555; font-size: 14px; margin: 0 0 24px">
          Les fetches Ticketmaster et les notifications n'ont pas été traités depuis plus de 3 heures.
        </p>
        <p style="margin-top: 24px">%s</p>
        """
            .formatted(e(detail), bouton(baseUrl + "/admin", "Aller à l'administration →"));
    return enveloppErreur("Worker inactif", corps, baseUrl);
  }

  /// Email de test de configuration Resend.
  ///
  /// @param baseUrl l'URL publique de base de l'application
  /// @return le HTML de l'email de test
  static String emailTest(String baseUrl) {
    String corps =
        """
        <p style="color: #555; margin: 0 0 16px">
          Si vous recevez cet email, la configuration Resend est correcte.
        </p>
        <p style="color: #aaa; font-size: 13px; margin: 0">
          Envoyé depuis l'administration Strapontin.
        </p>
        """;
    return envelopper("Test de configuration email", corps, baseUrl);
  }

  // ---- Helpers privés ----

  private static String envelopper(String titre, String corps, String baseUrl) {
    return enveloppAvecCouleur("#111", titre, corps, baseUrl);
  }

  private static String enveloppErreur(String titre, String corps, String baseUrl) {
    return enveloppAvecCouleur("#c00", titre, corps, baseUrl);
  }

  private static String enveloppAvecCouleur(
      String couleur, String titre, String corps, String baseUrl) {
    return """
    <!doctype html>
    <html lang="fr">
    <head>
      <meta charset="utf-8" />
      <meta name="viewport" content="width=device-width, initial-scale=1" />
    </head>
    <body style="font-family: system-ui, sans-serif; background: #f9f9f9; margin: 0; padding: 32px 16px">
      <div style="max-width: 520px; margin: 0 auto; background: #fff; border-radius: 8px; padding: 32px; border: 1px solid #e5e5e5">
        <h2 style="font-size: 20px; font-weight: 600; color: %s; margin: 0 0 8px">%s</h2>
        %s
      </div>
      <p style="text-align: center; color: #aaa; font-size: 12px; margin-top: 24px">
        Strapontin — <a href="%s/parametres" style="color: #aaa">Gérer mes préférences</a>
      </p>
    </body>
    </html>
    """
        .formatted(couleur, titre, corps, baseUrl);
  }

  private static String bouton(String href, String texte) {
    return """
    <a href="%s" style="display: inline-block; background: #111; color: #fff; text-decoration: none; padding: 10px 20px; border-radius: 6px; font-size: 14px; font-weight: 500">%s</a>
    """
        .formatted(href, texte);
  }

  /// Échappe les caractères HTML spéciaux d'une valeur variable.
  ///
  /// @param valeur la valeur à échapper (null retourne une chaîne vide)
  /// @return la valeur échappée, sûre pour insertion dans un attribut ou un nœud HTML
  static String e(Object valeur) {
    if (valeur == null) return "";
    return String.valueOf(valeur)
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;");
  }
}
