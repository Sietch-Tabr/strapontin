/*
 * Strapontin — concert finder
 * Copyright (C) 2025 Kwaite
 */
package fr.strapontin.worker;

import com.resend.Resend;
import com.resend.services.emails.model.CreateEmailOptions;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import nl.martijndwars.webpush.Notification;
import nl.martijndwars.webpush.PushService;
import nl.martijndwars.webpush.Subscription;
import org.apache.http.HttpResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/// Service de notification digest (email + push VAPID) pour le worker.
///
/// Lit les `user_matches` non notifiés, groupe par utilisateur, envoie
/// email via Resend et push VAPID, puis met à jour `notifie_le`.
///
/// Si `RESEND_API_KEY` ou les clés VAPID sont absentes de `app_config`,
/// la notification correspondante est ignorée sans erreur.
///
/// @since 2.0.0
/// @author Kwaite
@Service
public class NotificationDigestService {

  private static final Logger log = LoggerFactory.getLogger(NotificationDigestService.class);
  private static final String BASE_URL_DEFAUT = "https://strapontin.kwaite.fr";
  private static final String EXPEDITEUR = "Strapontin <noreply@strapontin.kwaite.fr>";

  private final ConfigServiceWorker configService;
  private final NamedParameterJdbcTemplate jdbc;

  public NotificationDigestService(
      ConfigServiceWorker configService, NamedParameterJdbcTemplate jdbc) {
    this.configService = configService;
    this.jdbc = jdbc;
  }

  /// Données d'un concert pour le digest.
  ///
  /// @param artiste   nom de l'artiste
  /// @param date      date ISO (yyyy-MM-dd)
  /// @param salle     nom de la salle
  /// @param ville     ville (peut être nulle)
  /// @param url       URL de billetterie (peut être nulle)
  /// @param concertId UUID du concert (string)
  public record ConcertNotif(
      String artiste, String date, String salle, String ville, String url, String concertId) {}

  /// Parcourt les matches non notifiés, envoie le digest, met à jour `notifie_le`.
  public void executerDigest() {
    log.info("notifier:digest démarré");
    long debut = System.currentTimeMillis();
    int nbUtilisateurs = 0;
    int nbMatchesNotifies = 0;
    String erreur = null;

    try {
      List<Map<String, Object>> lignes =
          jdbc.queryForList(
              """
              SELECT um.user_id, u.email, c.id::text AS concert_id,
                     c.artiste, c.date::text AS date, c.salle, c.ville, c.url
              FROM user_matches um
              JOIN concerts    c  ON c.id      = um.concert_id
              JOIN users       u  ON u.id      = um.user_id
              JOIN user_config uc ON uc.user_id = um.user_id
              WHERE um.notifie_le IS NULL
                AND um.masque_le  IS NULL
                AND uc.frequence_notifications != 'jamais'
              ORDER BY um.user_id, c.date
              """,
              Map.of());

      if (lignes.isEmpty()) {
        log.info("notifier:digest — aucun match non notifié");
        return;
      }

      Map<String, String> emailsUtilisateurs = new LinkedHashMap<>();
      Map<String, List<ConcertNotif>> parUtilisateur = new LinkedHashMap<>();
      for (Map<String, Object> ligne : lignes) {
        String userId = (String) ligne.get("user_id");
        emailsUtilisateurs.putIfAbsent(userId, (String) ligne.get("email"));
        parUtilisateur
            .computeIfAbsent(userId, _ -> new ArrayList<>())
            .add(
                new ConcertNotif(
                    (String) ligne.get("artiste"),
                    (String) ligne.get("date"),
                    (String) ligne.get("salle"),
                    (String) ligne.get("ville"),
                    (String) ligne.get("url"),
                    (String) ligne.get("concert_id")));
      }

      Timestamp maintenant = Timestamp.from(Instant.now());
      for (Map.Entry<String, List<ConcertNotif>> entree : parUtilisateur.entrySet()) {
        String userId = entree.getKey();
        List<ConcertNotif> concerts = entree.getValue();
        try {
          envoyerDigest(userId, emailsUtilisateurs.get(userId), concerts);
        } catch (Exception e) {
          log.error("notifier:digest erreur envoi pour user={} : {}", userId, e.getMessage());
        }
        List<UUID> concertIds = concerts.stream().map(c -> UUID.fromString(c.concertId())).toList();
        int updated =
            jdbc.update(
                """
                UPDATE user_matches SET notifie_le = :maintenant
                WHERE user_id = :userId AND concert_id IN (:concertIds)
                """,
                new MapSqlParameterSource()
                    .addValue("userId", userId)
                    .addValue("maintenant", maintenant)
                    .addValue("concertIds", concertIds));
        nbUtilisateurs++;
        nbMatchesNotifies += updated;
      }
    } catch (Exception e) {
      erreur = e.getMessage();
      log.error("notifier:digest échoué", e);
    } finally {
      enregistrerStat(System.currentTimeMillis() - debut, nbMatchesNotifies, erreur);
      log.info("notifier:digest terminé users={} concerts={}", nbUtilisateurs, nbMatchesNotifies);
    }
  }

  private boolean envoyerDigest(String userId, String email, List<ConcertNotif> concerts) {
    boolean emailOk = envoyerEmail(email, concerts);
    boolean pushOk = envoyerPush(userId, concerts);
    return emailOk || pushOk;
  }

  private boolean envoyerEmail(String email, List<ConcertNotif> concerts) {
    Optional<String> cle = configService.lireConfig("RESEND_API_KEY");
    if (cle.isEmpty()) {
      log.debug("RESEND_API_KEY absent — digest email ignoré");
      return false;
    }
    String replyTo = configService.lireConfig("CONTACT_EMAIL").orElse("admin@strapontin.kwaite.fr");
    String baseUrl = configService.lireConfig("PUBLIC_BASE_URL").orElse(BASE_URL_DEFAUT);

    int nb = concerts.size();
    String sujet =
        nb == 1
            ? "1 nouveau concert correspond à vos préférences"
            : "%d nouveaux concerts correspondent à vos préférences".formatted(nb);
    try {
      Resend resend = new Resend(cle.get());
      CreateEmailOptions options =
          CreateEmailOptions.builder()
              .from(EXPEDITEUR)
              .replyTo(replyTo)
              .to(List.of(email))
              .subject(sujet)
              .html(construireCorpsEmail(sujet, concerts, baseUrl))
              .text(sujet)
              .build();
      resend.emails().send(options);
      log.info("Digest email envoyé : {} ({} concert(s))", email, nb);
      return true;
    } catch (Exception e) {
      log.error("Échec envoi digest email : {}", email, e);
      return false;
    }
  }

  private boolean envoyerPush(String userId, List<ConcertNotif> concerts) {
    Optional<String> clePublique = configService.lireConfig("VAPID_PUBLIC_KEY");
    Optional<String> clePrivee = configService.lireConfig("VAPID_PRIVATE_KEY");
    if (clePublique.isEmpty() || clePrivee.isEmpty()) {
      log.debug("VAPID non configuré — push digest ignoré pour {}", userId);
      return false;
    }
    List<Map<String, Object>> abonnements =
        jdbc.queryForList(
            "SELECT id, endpoint, p256dh, auth FROM push_abonnements WHERE user_id = :userId",
            Map.of("userId", userId));
    if (abonnements.isEmpty()) return false;

    String baseUrl = configService.lireConfig("PUBLIC_BASE_URL").orElse(BASE_URL_DEFAUT);
    String contactEmail =
        configService.lireConfig("CONTACT_EMAIL").orElse("admin@strapontin.kwaite.fr");
    PushService pushService;
    try {
      pushService = new PushService(clePublique.get(), clePrivee.get(), "mailto:" + contactEmail);
    } catch (Exception e) {
      log.error("Impossible d'initialiser le service VAPID", e);
      return false;
    }

    String payload = construirePayloadPush(concerts, baseUrl);
    int nbEnvoyes = 0;
    for (Map<String, Object> ab : abonnements) {
      try {
        Subscription subscription =
            new Subscription(
                (String) ab.get("endpoint"),
                new Subscription.Keys((String) ab.get("p256dh"), (String) ab.get("auth")));
        Notification notification = new Notification(subscription, payload);
        HttpResponse response = pushService.send(notification);
        int status = response.getStatusLine().getStatusCode();
        if (status == 404 || status == 410) {
          jdbc.update("DELETE FROM push_abonnements WHERE id = :id", Map.of("id", ab.get("id")));
          log.info("Abonnement push révoqué : {} — {}", userId, ab.get("endpoint"));
        } else {
          nbEnvoyes++;
        }
      } catch (Exception e) {
        log.warn("Push: échec envoi pour {} — {}", userId, ab.get("endpoint"), e);
      }
    }
    if (nbEnvoyes > 0) log.info("Push digest envoyé : {} ({} abonnement(s))", userId, nbEnvoyes);
    return nbEnvoyes > 0;
  }

  private static String construirePayloadPush(List<ConcertNotif> concerts, String baseUrl) {
    ConcertNotif premier = concerts.get(0);
    String titre =
        concerts.size() == 1
            ? premier.artiste() + " — " + premier.salle()
            : concerts.size() + " nouveaux concerts";
    String corps =
        concerts.size() == 1
            ? formaterDate(premier.date())
                + (premier.ville() != null ? " · " + premier.ville() : "")
            : concerts.stream()
                .map(c -> c.artiste() + " · " + formaterDate(c.date()))
                .collect(Collectors.joining("\n"));
    String url =
        concerts.size() == 1
            ? baseUrl
                + "/spectacles?artiste="
                + URLEncoder.encode(premier.artiste(), StandardCharsets.UTF_8)
            : baseUrl + "/spectacles";
    return """
    {"title":"%s","body":"%s","icon":"%s/images/icon-192.png","url":"%s"}\
    """
        .formatted(echapperJson(titre), echapperJson(corps), baseUrl, echapperJson(url));
  }

  private static String construireCorpsEmail(
      String sujet, List<ConcertNotif> concerts, String baseUrl) {
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
            .collect(Collectors.joining("\n"));
    return """
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
    """
        .formatted(sujet, sujet, lignes, baseUrl, baseUrl);
  }

  private void enregistrerStat(long dureeMs, int nbNotifies, String erreur) {
    try {
      Map<String, Object> params = new LinkedHashMap<>();
      params.put("typeJob", "notifier:digest");
      params.put("now", Timestamp.from(Instant.now()));
      params.put("dureeMs", (int) Math.min(dureeMs, Integer.MAX_VALUE));
      params.put("nbNotifies", nbNotifies);
      params.put("erreur", erreur);
      jdbc.update(
          """
          INSERT INTO fetch_jobs (type_job, derniere_exec, duree_ms, nb_inseres, nb_mis_a_jour, erreur)
          VALUES (:typeJob, :now, :dureeMs, 0, :nbNotifies, :erreur)
          ON CONFLICT (type_job) DO UPDATE SET
            derniere_exec  = EXCLUDED.derniere_exec,
            duree_ms       = EXCLUDED.duree_ms,
            nb_inseres     = EXCLUDED.nb_inseres,
            nb_mis_a_jour  = EXCLUDED.nb_mis_a_jour,
            erreur         = EXCLUDED.erreur
          """,
          params);
    } catch (Exception e) {
      log.warn("Impossible d'enregistrer stat notifier:digest : {}", e.getMessage());
    }
  }

  private static String formaterDate(String dateIso) {
    LocalDate date = LocalDate.parse(dateIso);
    return DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL)
        .withLocale(Locale.FRANCE)
        .format(date);
  }

  private static String echapperJson(String s) {
    return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
  }
}
