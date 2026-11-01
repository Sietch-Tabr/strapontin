/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.admin;

import fr.strapontin.sources.http.CadenceurAppels;
import fr.strapontin.web.ErreurMetier;
import fr.strapontin.web.config.ConfigService;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

/**
 * Configuration applicative de l'onglet admin : statuts, sauvegarde,
 * validation de format, test de la clé Ticketmaster — extrait
 * d'{@code AdminService} (resté dédié aux comptes).
 * @since 2.1.0 (extrait dans sa forme actuelle en 2.7.0)
 * @author Kwaite
 */
@Service
public class ConfigAdminService {

  private static final Logger LOG = LoggerFactory.getLogger(ConfigAdminService.class);
  private static final String CLE_PUBLIC_BASE_URL = "PUBLIC_BASE_URL";

  private static final List<String> CLES_AUTORISEES =
      List.of(
          "TICKETMASTER_API_KEY",
          "RESEND_API_KEY",
          "RESEND_WEBHOOK_SECRET",
          "CONTACT_EMAIL",
          "ADMIN_ALERT_EMAIL",
          CLE_PUBLIC_BASE_URL,
          "SIGNAL_LIEN",
          "VAPID_PUBLIC_KEY",
          "VAPID_PRIVATE_KEY");
  private static final Set<String> CLES_EN_CLAIR =
      Set.of("CONTACT_EMAIL", "ADMIN_ALERT_EMAIL", CLE_PUBLIC_BASE_URL, "SIGNAL_LIEN");
  private static final Set<String> CLES_OBLIGATOIRES = Set.of("CONTACT_EMAIL", CLE_PUBLIC_BASE_URL);
  private static final Pattern FORMAT_TM_KEY = Pattern.compile("^[A-Za-z0-9]{16,64}$");
  private static final Pattern FORMAT_RESEND_KEY = Pattern.compile("^re_[A-Za-z0-9_-]{10,}$");
  private static final Pattern FORMAT_WEBHOOK_SECRET =
      Pattern.compile("^whsec_[A-Za-z0-9+/=]{16,}$");
  // Base64 URL-safe sans padding d'une clé EC P-256 : 65 octets (point non
  // compressé, clé publique) ou 32 octets (scalaire, clé privée) — voir
  // VapidService.genererVapid().
  private static final Pattern FORMAT_VAPID_PUBLIC_KEY = Pattern.compile("^[A-Za-z0-9_-]{87}$");
  private static final Pattern FORMAT_VAPID_PRIVATE_KEY = Pattern.compile("^[A-Za-z0-9_-]{43}$");

  private static final Map<String, FormatAttendu> FORMATS_CONFIG =
      Map.of(
          "TICKETMASTER_API_KEY",
              new FormatAttendu(
                  FORMAT_TM_KEY,
                  "Format de clé Ticketmaster invalide (16 à 64 caractères alphanumériques)."),
          "RESEND_API_KEY",
              new FormatAttendu(
                  FORMAT_RESEND_KEY,
                  "La clé Resend doit commencer par re_ suivi de caractères alphanumériques."),
          "RESEND_WEBHOOK_SECRET",
              new FormatAttendu(
                  FORMAT_WEBHOOK_SECRET,
                  "La clé webhook Resend doit commencer par whsec_ suivi de caractères base64."),
          "VAPID_PUBLIC_KEY",
              new FormatAttendu(
                  FORMAT_VAPID_PUBLIC_KEY,
                  "La clé publique VAPID doit être une clé EC P-256 encodée en Base64 URL-safe"
                      + " (87 caractères)."),
          "VAPID_PRIVATE_KEY",
              new FormatAttendu(
                  FORMAT_VAPID_PRIVATE_KEY,
                  "La clé privée VAPID doit être une clé EC P-256 encodée en Base64 URL-safe"
                      + " (43 caractères)."));

  private static final String TM_URL_TEST =
      "https://app.ticketmaster.com/discovery/v2/events.json"
          + "?apikey={cle}&size=1&countryCode=FR&classificationName=music";

  private final ConfigService configService;
  private final LogAdminService logAdminService;
  private final RestClient restClient;
  private final CadenceurAppels cadenceurTicketmaster;

  private record FormatAttendu(Pattern pattern, String messageErreur) {}

  /**
   * @param configService lecture/écriture de la configuration applicative
   * @param logAdminService journalisation des modifications de configuration
   * @param restClient client HTTP pour les tests d'API externes
   * @param cadenceurTicketmaster cadenceur partagé inter-processus avec le
   *     worker (voir {@link
   *     fr.strapontin.web.WebConfiguration#cadenceurTicketmaster})
   */
  public ConfigAdminService(
      final ConfigService configService,
      final LogAdminService logAdminService,
      final RestClient restClient,
      final CadenceurAppels cadenceurTicketmaster) {
    this.configService = configService;
    this.logAdminService = logAdminService;
    this.restClient = restClient;
    this.cadenceurTicketmaster = cadenceurTicketmaster;
  }

  /**
   * Teste la validité de la clé Ticketmaster API en effectuant un appel
   * minimal.
   *
   * @return {@code true} si la clé est configurée et acceptée par l'API TM
   *     (HTTP 200)
   */
  public boolean testerApiTm() {
    final Optional<String> cle = configService.lireConfig("TICKETMASTER_API_KEY");
    if (cle.isEmpty()) {
      return false;
    }
    try {
      cadenceurTicketmaster.attendre();
      restClient.get().uri(TM_URL_TEST, cle.get()).retrieve().toBodilessEntity();
      return true;
    } catch (Exception e) {
      LOG.warn("Test clé TM : réponse invalide — {}", e.getMessage());
      return false;
    }
  }

  /**
   * Retourne le statut des clés de configuration autorisées.
   *
   * @return statuts de configuration
   */
  public List<ConfigStatut> lireConfigStatuts() {
    final Set<String> presentes = configService.lireConfigsPresentes();
    final List<ConfigStatut> statuts = new ArrayList<>();
    for (final String cle : CLES_AUTORISEES) {
      final boolean presente = presentes.contains(cle);
      final String valeur =
          (presente && CLES_EN_CLAIR.contains(cle))
              ? configService.lireConfig(cle).orElse(null)
              : null;
      statuts.add(new ConfigStatut(cle, presente, valeur, !CLES_EN_CLAIR.contains(cle)));
    }
    return statuts;
  }

  /**
   * Valide et sauvegarde une clé de configuration.
   *
   * @param cle clé à mettre à jour
   * @param valeur nouvelle valeur (null pour effacer)
   * @param adminId identifiant de l'admin
   * @throws ErreurMetier si la clé est non autorisée ou le format invalide
   */
  public void sauvegarderConfig(final String cle, final String valeur, final String adminId) {
    if (!CLES_AUTORISEES.contains(cle)) {
      throw new ErreurMetier("Clé de configuration invalide.");
    }
    final boolean valeurVide = valeur == null || valeur.isEmpty();
    if (valeurVide) {
      if (CLES_OBLIGATOIRES.contains(cle)) {
        throw new ErreurMetier("La valeur ne peut pas être vide pour " + cle + ".");
      }
    } else {
      validerFormatOuLever(cle, valeur);
    }
    configService.sauvegarderConfig(cle, valeurVide ? null : valeur);
    logAdminService.logAdmin("info", "admin", "Config sauvegardée : " + cle, Map.of("cle", cle));
  }

  private void validerFormatOuLever(final String cle, final String valeur) {
    final String erreur = validerFormatConfig(cle, valeur);
    if (erreur != null) {
      throw new ErreurMetier(erreur);
    }
  }

  private String validerFormatConfig(final String cle, final String valeur) {
    final FormatAttendu format = FORMATS_CONFIG.get(cle);
    if (format != null && !format.pattern().matcher(valeur).matches()) {
      return format.messageErreur();
    }
    return CLE_PUBLIC_BASE_URL.equals(cle) ? validerFormatUrlPublique(valeur) : null;
  }

  private static String validerFormatUrlPublique(final String valeur) {
    try {
      final URI uri = URI.create(valeur);
      final boolean estIp = estAdresseIpOuLocalhost(uri.getHost());
      if (!estIp && !"https".equals(uri.getScheme())) {
        return "L'URL doit commencer par https:// pour un domaine public.";
      }
    } catch (Exception _) {
      return "Format d'URL invalide.";
    }
    return null;
  }

  private static boolean estAdresseIpOuLocalhost(final String host) {
    return "localhost".equals(host)
        || (host != null && host.matches("(\\d{1,3}\\.){3}\\d{1,3}"))
        || (host != null && host.contains(":") && host.matches("[0-9a-fA-F:]+"));
  }
}
