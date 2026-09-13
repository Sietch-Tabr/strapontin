/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker;

import fr.strapontin.core.UtilitairesChaine;
import java.util.List;

/**
 * Construction du corps HTML de l'email de digest.
 *
 * <p>Extrait de {@link NotificationDigestService} pour rester sous la limite de 500 lignes par
 * fichier.
 *
 * @since 2.9.1
 * @author Kwaite
 */
final class EmailDigestTemplate {

  private static final String GABARIT_CORPS_EMAIL =
      """
      <!DOCTYPE html>
      <html lang="fr">
      <head><meta charset="utf-8"><title>%s</title></head>
      <body style="font-family:system-ui,sans-serif;background:#f9f9f9;margin:0;padding:32px 16px">
      <div style="max-width:520px;margin:0 auto;background:#fff;border-radius:8px;padding:32px;border:1px solid #e5e5e5">
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
      </div>
      <p style="text-align:center;color:#aaa;font-size:12px;margin-top:24px">
        Strapontin — <a href="%s/parametres" style="color:#aaa">Gérer mes préférences</a>
      </p>
      </body>
      </html>
      """;

  private EmailDigestTemplate() {}

  /* package-private : appelé par NotificationEmailDigestService (même package). */
  static String construireCorpsEmail(
      final String sujet,
      final List<NotificationDigestService.ConcertNotif> concerts,
      final String baseUrl) {
    final StringBuilder lignes = new StringBuilder();
    String artistePrecedent = null;
    for (final NotificationDigestService.ConcertNotif c : concerts) {
      lignes.append(construireLigneConcertEmail(c, artistePrecedent));
      artistePrecedent = c.artiste();
    }
    return GABARIT_CORPS_EMAIL.formatted(sujet, sujet, lignes, baseUrl, baseUrl);
  }

  // Rupture visuelle plus marquée qu'entre deux concerts d'un même artiste — sans elle, un
  // digest couvrant plusieurs artistes se lit comme une liste continue.
  private static String construireLigneConcertEmail(
      final NotificationDigestService.ConcertNotif concert, final String artistePrecedent) {
    final String lieu =
        concert.ville() != null
            ? e(concert.salle()) + " · " + e(concert.ville())
            : e(concert.salle());
    final String lien =
        concert.url() != null
            ? " — <a href=\"%s\" style=\"color:#111;font-size:13px\">Billets →</a>"
                .formatted(e(concert.url()))
            : "";
    final String prevente =
        "presale".equals(concert.statut())
            ? " · <span style=\"color:#c026d3;font-weight:600\">Prévente</span>"
            : "";
    final String stylecel =
        artistePrecedent != null && !artistePrecedent.equals(concert.artiste())
            ? "padding:16px 0 10px;border-top:2px solid #ddd;vertical-align:top"
            : "padding:10px 0;border-bottom:1px solid #f0f0f0;vertical-align:top";
    return """
    <tr>
      <td style="%s">
        <div style="font-weight:600;color:#111;font-size:14px">%s</div>
        <div style="color:#666;font-size:13px;margin-top:2px">%s%s%s</div>
        <div style="color:#999;font-size:12px;margin-top:2px">%s</div>
      </td>
    </tr>
    """
        .formatted(
            stylecel,
            e(concert.artiste()),
            NotificationDigestService.formaterDate(concert.date()),
            prevente,
            lien,
            lieu);
  }

  // Nom volontairement court : appelé à de nombreuses reprises dans des lignes de formatage HTML
  // déjà denses (voir le gabarit ci-dessus) — un nom plus long nuirait à leur lisibilité.
  @SuppressWarnings("PMD.ShortMethodName")
  private static String e(final String valeur) {
    return UtilitairesChaine.echapperHtml(valeur);
  }
}
