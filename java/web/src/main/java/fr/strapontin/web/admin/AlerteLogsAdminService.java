/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.admin;

import fr.strapontin.web.config.ConfigService;
import fr.strapontin.web.email.EmailAlerteService;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Alerte l'admin par digest email sur les nouvelles entrées {@code
 * logs_admin error}, toutes les 15 minutes — comme {@link
 * WatchdogWorkerService} mais curseur persisté en {@code app_config}
 * (survit aux redéploiements fréquents, contrairement au watchdog).
 * @since 2.6.1
 * @author Kwaite
 */
@Service
public class AlerteLogsAdminService {

  private static final Logger LOG = LoggerFactory.getLogger(AlerteLogsAdminService.class);
  // Ancien curseur temporel (ms), relu une fois pour initialiser l'identifiant.
  private static final String CLE_CURSEUR_TS = "ALERTE_LOGS_ADMIN_CURSEUR";
  private static final String CLE_CURSEUR_ID = "ALERTE_LOGS_ADMIN_CURSEUR_ID";
  private static final String CLE_ENVOIS_JOUR = "ALERTE_LOGS_ADMIN_ENVOIS_JOUR";
  // Plafond journalier : le quota Resend (100/jour) reste aux emails
  // fonctionnels ; au-delà, les erreurs attendent le digest du lendemain.
  /* package-private : élargi pour AlerteLogsAdminServiceTest. */
  static final int MAX_DIGESTS_PAR_JOUR = 8;

  private final LogAdminService logAdminService;
  private final ConfigService configService;
  private final EmailAlerteService emailAlerteService;
  private final Clock horloge;

  /**
   * @param logAdminService lecture des entrées d'erreur depuis le curseur
   * @param configService lecture/écriture du curseur et du compteur journalier
   * @param emailAlerteService envoi du digest et résolution du destinataire
   *     d'alerte
   * @param horloge horloge de référence du compteur journalier
   */
  public AlerteLogsAdminService(
      final LogAdminService logAdminService,
      final ConfigService configService,
      final EmailAlerteService emailAlerteService,
      final Clock horloge) {
    this.logAdminService = logAdminService;
    this.configService = configService;
    this.emailAlerteService = emailAlerteService;
    this.horloge = horloge;
  }

  /**
   * Digest des nouvelles erreurs toutes les 15 minutes. Le curseur n'avance
   * qu'après envoi réussi : échec Resend, destinataire absent ou plafond de
   * {@value #MAX_DIGESTS_PAR_JOUR} digests par jour reportent les erreurs.
   */
  @Scheduled(cron = "0 */15 * * * *")
  public void verifier() {
    final long curseur = lireCurseur();
    final List<LogAdminService.EntreeLogAdmin> erreurs = logAdminService.lireErreursDepuis(curseur);
    if (erreurs.isEmpty()) {
      return;
    }

    final LocalDate aujourdhui = LocalDate.now(horloge);
    final int envoisDuJour = lireEnvoisDuJour(aujourdhui);
    if (envoisDuJour >= MAX_DIGESTS_PAR_JOUR) {
      LOG.warn(
          "alerteLogsAdmin: plafond de {} digests atteint — {} erreur(s) reportée(s) à demain",
          MAX_DIGESTS_PAR_JOUR,
          erreurs.size());
      return;
    }

    final Optional<String> email = emailAlerteService.obtenirDestinataireAlerte();
    if (email.isEmpty()) {
      LOG.warn(
          "alerteLogsAdmin: aucun destinataire d'alerte disponible — {} erreur(s) en attente,"
              + " curseur non avancé",
          erreurs.size());
      return;
    }

    final boolean envoye = emailAlerteService.envoyerAlerteLogsAdmin(email.get(), erreurs);
    if (!envoye) {
      LOG.warn(
          "alerteLogsAdmin: échec d'envoi — {} erreur(s) en attente, curseur non avancé",
          erreurs.size());
      return;
    }

    final long nouveauCurseur = erreurs.get(erreurs.size() - 1).id();
    configService.sauvegarderConfig(CLE_CURSEUR_ID, String.valueOf(nouveauCurseur));
    configService.sauvegarderConfig(CLE_ENVOIS_JOUR, aujourdhui + ":" + (envoisDuJour + 1));
  }

  private long lireCurseur() {
    final Optional<String> curseurId = configService.lireConfig(CLE_CURSEUR_ID);
    if (curseurId.isPresent()) {
      return Long.parseLong(curseurId.get());
    }
    return configService
        .lireConfig(CLE_CURSEUR_TS)
        .map(ts -> logAdminService.idMaxJusquA(Long.parseLong(ts)))
        .orElse(0L);
  }

  // Format "AAAA-MM-JJ:nombre" ; un autre jour remet le compteur à zéro.
  private int lireEnvoisDuJour(final LocalDate aujourdhui) {
    return configService
        .lireConfig(CLE_ENVOIS_JOUR)
        .filter(v -> v.startsWith(aujourdhui + ":"))
        .map(v -> Integer.parseInt(v.substring(v.indexOf(':') + 1)))
        .orElse(0);
  }
}
