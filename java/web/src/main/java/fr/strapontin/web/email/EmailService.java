/*
 * Strapontin — concert finder
 * Copyright (C) 2025 Kwaite
 */
package fr.strapontin.web.email;

import com.resend.Resend;
import com.resend.core.exception.ResendException;
import com.resend.services.emails.model.CreateEmailOptions;
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
import org.springframework.web.util.HtmlUtils;

/// Service d'email transactionnel via Resend (portage de {@code email.ts}).
///
/// <p>Si {@code RESEND_API_KEY} est absent de la configuration, toutes les méthodes
/// retournent silencieusement sans erreur (comportement identique au TypeScript).
///
/// <p>Couvre l'ensemble des emails : invitation, reset MDP, signalement,
/// digest concerts, alerte worker inactif.
///
/// @since 2.0.0
/// @author Kwaite
@Service
public class EmailService {

  private static final Logger log = LoggerFactory.getLogger(EmailService.class);
  private static final String EXPEDITEUR = "Strapontin <noreply@strapontin.kwaite.fr>";

  private final ConfigService configService;

  public EmailService(ConfigService configService) {
    this.configService = configService;
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

  private record ContexteEmail(Resend client, String replyTo, String baseUrl) {}

  private Optional<ContexteEmail> obtenirContexte() {
    Optional<String> cle = configService.lireConfig("RESEND_API_KEY");
    if (cle.isEmpty()) {
      return Optional.empty();
    }
    String replyTo = configService.lireConfig("CONTACT_EMAIL").orElse("admin@strapontin.kwaite.fr");
    String baseUrl =
        configService.lireConfig("PUBLIC_BASE_URL").orElse("https://strapontin.kwaite.fr");
    return Optional.of(new ContexteEmail(new Resend(cle.get()), replyTo, baseUrl));
  }

  private static String enTeteHtml(String titre) {
    return """
    <!DOCTYPE html>
    <html lang="fr">
    <head>
    <meta charset="utf-8">
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <title>%s</title>
    </head>
    <body style="font-family:system-ui,sans-serif;background:#f9f9f9;margin:0;padding:32px 16px">
    <div style="max-width:520px;margin:0 auto;background:#fff;border-radius:8px;padding:32px;border:1px solid #e5e5e5">
    """
        .formatted(titre);
  }

  private static String piedHtml(String baseUrl) {
    return """
    </div>
    <p style="text-align:center;color:#aaa;font-size:12px;margin-top:24px">
      Strapontin — <a href="%s/parametres" style="color:#aaa">Gérer mes préférences</a>
    </p>
    </body>
    </html>
    """
        .formatted(baseUrl);
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
              .from(EXPEDITEUR)
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
  /// @param email      l'adresse email du destinataire
  /// @param identifiant l'identifiant temporaire
  /// @param lienReset  le lien de création de mot de passe (valable 1 heure)
  public void envoyerInvitation(String email, String identifiant, String lienReset) {
    Optional<ContexteEmail> ctx = obtenirContexte();
    if (ctx.isEmpty()) {
      log.warn("RESEND_API_KEY absent — email invitation non envoyé");
      return;
    }

    String corps =
        enTeteHtml("Votre invitation Strapontin")
            + """
            <h2 style="font-size:20px;font-weight:600;color:#111;margin:0 0 8px">Bienvenue sur Strapontin</h2>
            <p style="color:#555;margin:0 0 24px">Votre compte a été créé. Commencez par choisir votre mot de passe :</p>
            <a href="%s"
               style="display:inline-block;background:#111;color:#fff;text-decoration:none;padding:10px 20px;border-radius:6px;font-size:14px;font-weight:500;margin-bottom:24px">
              Créer mon mot de passe →
            </a>
            <p style="color:#aaa;font-size:13px;margin:16px 0 0">
              Ce lien est valable 1 heure. Une fois votre mot de passe créé, connectez-vous avec votre identifiant temporaire :
              <code style="font-family:monospace;background:#f5f5f5;padding:2px 6px;border-radius:4px">%s</code>
            </p>
            <p style="color:#aaa;font-size:13px;margin:8px 0 0">
              Il vous sera demandé de choisir un identifiant définitif à la première connexion.
            </p>
            """
                .formatted(lienReset, HtmlUtils.htmlEscape(identifiant))
            + piedHtml(ctx.get().baseUrl());

    String texte =
        """
        Bienvenue sur Strapontin

        Votre compte a été créé. Commencez par choisir votre mot de passe (lien valable 1 heure) :

        %s

        Une fois votre mot de passe créé, connectez-vous avec votre identifiant temporaire : %s

        Il vous sera demandé de choisir un identifiant définitif à la première connexion.
        """
            .formatted(lienReset, identifiant);

    try {
      envoyer(ctx.get(), email, ctx.get().replyTo(), "Votre invitation Strapontin", corps, texte);
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

    String corps =
        enTeteHtml("Réinitialisation de votre mot de passe")
            + """
            <h2 style="font-size:20px;font-weight:600;color:#111;margin:0 0 8px">Réinitialisation du mot de passe</h2>
            <p style="color:#555;margin:0 0 24px">
              Vous avez demandé la réinitialisation de votre mot de passe. Cliquez sur le bouton ci-dessous.
              Ce lien est valable 1 heure.
            </p>
            <a href="%s"
               style="display:inline-block;background:#111;color:#fff;text-decoration:none;padding:10px 20px;border-radius:6px;font-size:14px;font-weight:500">
              Réinitialiser mon mot de passe →
            </a>
            <p style="color:#aaa;font-size:13px;margin-top:24px">
              Si vous n'êtes pas à l'origine de cette demande, ignorez cet email.
            </p>
            """
                .formatted(lienReset)
            + piedHtml(ctx.get().baseUrl());

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
          ctx.get().replyTo(),
          "Réinitialisation de votre mot de passe Strapontin",
          corps,
          texte);
      log.info("Email reset MDP envoyé : {}", email);
    } catch (Exception e) {
      log.error("Échec envoi reset MDP : {}", email, e);
    }
  }

  /// Envoie un signalement d'utilisateur à l'adresse de contact.
  ///
  /// @param type             le type ({@code bug}, {@code suggestion}, {@code question})
  /// @param message          le message
  /// @param expediteurEmail  l'email de l'expéditeur
  /// @param expediteurNom    le nom de l'expéditeur
  public void envoyerSignalement(
      String type, String message, String expediteurEmail, String expediteurNom) {
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
        enTeteHtml("Signalement — " + typeLabel)
            + """
            <h2 style="font-size:20px;font-weight:600;color:#111;margin:0 0 8px">Signalement — %s</h2>
            <table style="border-collapse:collapse;width:100%%;margin-bottom:24px">
              <tr>
                <td style="padding:8px 0;color:#888;font-size:14px;width:130px">Utilisateur</td>
                <td style="padding:8px 0;font-size:14px;color:#111">%s</td>
              </tr>
              <tr>
                <td style="padding:8px 0;color:#888;font-size:14px">Email</td>
                <td style="padding:8px 0;font-size:14px;color:#111">%s</td>
              </tr>
            </table>
            <p style="color:#555;font-size:14px;white-space:pre-wrap;border-left:3px solid #e5e5e5;padding-left:12px;margin:0">%s</p>
            """
                .formatted(
                    typeLabel,
                    HtmlUtils.htmlEscape(expediteurNom),
                    HtmlUtils.htmlEscape(expediteurEmail),
                    HtmlUtils.htmlEscape(message))
            + piedHtml(ctx.get().baseUrl());

    String texte =
        "Signalement — %s\n\nUtilisateur : %s\nEmail : %s\n\n%s"
            .formatted(typeLabel, expediteurNom, expediteurEmail, message);

    try {
      envoyer(
          ctx.get(),
          ctx.get().replyTo(),
          expediteurEmail,
          "[Strapontin] Signalement — %s — %s".formatted(typeLabel, expediteurNom),
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
  /// @return {@code true} si l'email a été envoyé avec succès
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

    String lignes =
        concerts.stream()
            .map(
                c -> {
                  String lieu = c.ville() != null ? c.salle() + " · " + c.ville() : c.salle();
                  String lien =
                      c.url() != null
                          ? " — <a href=\"%s\" style=\"color:#111;font-size:13px\">Billets →</a>"
                              .formatted(c.url())
                          : "";
                  return """
                  <tr>
                    <td style="padding:10px 0;border-bottom:1px solid #f0f0f0;vertical-align:top">
                      <div style="font-weight:600;color:#111;font-size:14px">%s</div>
                      <div style="color:#666;font-size:13px;margin-top:2px">%s%s</div>
                      <div style="color:#999;font-size:12px;margin-top:2px">%s</div>
                    </td>
                  </tr>\
                  """
                      .formatted(c.artiste(), formaterDate(c.date()), lien, lieu);
                })
            .collect(java.util.stream.Collectors.joining("\n"));

    String corps =
        enTeteHtml(sujet)
            + """
            <h2 style="font-size:20px;font-weight:600;color:#111;margin:0 0 8px">%s</h2>
            <p style="color:#555;font-size:14px;margin:0 0 24px">Voici les nouveaux concerts ajoutés :</p>
            <table style="border-collapse:collapse;width:100%%">
            %s
            </table>
            <p style="margin-top:24px">
              <a href="%s/spectacles"
                 style="display:inline-block;background:#111;color:#fff;text-decoration:none;padding:10px 20px;border-radius:6px;font-size:14px;font-weight:500">
                Voir mes spectacles →
              </a>
            </p>
            """
                .formatted(sujet, lignes, ctx.get().baseUrl())
            + piedHtml(ctx.get().baseUrl());

    try {
      envoyer(ctx.get(), email, ctx.get().replyTo(), sujet, corps, sujet);
      log.info("Digest envoyé : {} ({} concert(s))", email, nb);
      return true;
    } catch (Exception e) {
      log.error("Échec envoi digest : {}", email, e);
      return false;
    }
  }

  /// Envoie une alerte à l'admin quand le worker est inactif depuis plus de 3 heures.
  ///
  /// @param email                l'adresse email de l'administrateur
  /// @param derniereActiviteIso  la date ISO du dernier job complété, ou {@code null}
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

    String sujet = "Strapontin — worker inactif";
    String corps =
        enTeteHtml(sujet)
            + """
            <h2 style="font-size:20px;font-weight:600;color:#c00;margin:0 0 8px">Worker inactif</h2>
            <p style="color:#555;font-size:14px;margin:0 0 16px">%s</p>
            <p style="color:#555;font-size:14px;margin:0 0 24px">Les fetches Ticketmaster et les notifications n'ont pas été traités depuis plus de 3 heures.</p>
            <p style="margin-top:24px">
              <a href="%s/admin"
                 style="display:inline-block;background:#111;color:#fff;text-decoration:none;padding:10px 20px;border-radius:6px;font-size:14px;font-weight:500">
                Aller à l'administration →
              </a>
            </p>
            """
                .formatted(detail, ctx.get().baseUrl())
            + piedHtml(ctx.get().baseUrl());

    try {
      envoyer(
          ctx.get(),
          email,
          ctx.get().replyTo(),
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
