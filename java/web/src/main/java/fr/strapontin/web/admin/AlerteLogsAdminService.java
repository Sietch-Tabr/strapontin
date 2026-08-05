/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.admin;

import fr.strapontin.web.config.ConfigService;
import fr.strapontin.web.email.EmailAlerteService;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/// Alerte l'administrateur par email digest sur les nouvelles entrées {@code logs_admin} de
/// niveau {@code error}, toutes les 15 minutes.
///
/// <p>Même pattern que {@link WatchdogWorkerService} (alerte proactive sur incident), mais
/// curseur persisté en {@code app_config} plutôt qu'en mémoire — nécessaire pour survivre aux
/// redéploiements fréquents du projet, contrairement au watchdog worker dont le pire cas (une
/// alerte manquée après redéploiement) est sans conséquence.
///
/// @since 2.6.1
/// @author Kwaite
@Service
public class AlerteLogsAdminService {

  private static final Logger log = LoggerFactory.getLogger(AlerteLogsAdminService.class);
  private static final String CLE_CURSEUR = "ALERTE_LOGS_ADMIN_CURSEUR";

  private final LogAdminService logAdminService;
  private final ConfigService configService;
  private final EmailAlerteService emailAlerteService;

  /// @param logAdminService   lecture des entrées d'erreur depuis le curseur
  /// @param configService     lecture/écriture du curseur
  /// @param emailAlerteService envoi du digest et résolution du destinataire d'alerte
  public AlerteLogsAdminService(
      LogAdminService logAdminService,
      ConfigService configService,
      EmailAlerteService emailAlerteService) {
    this.logAdminService = logAdminService;
    this.configService = configService;
    this.emailAlerteService = emailAlerteService;
  }

  /// Vérifie les nouvelles erreurs toutes les 15 minutes et envoie un digest agrégé.
  ///
  /// <p>Le curseur n'avance qu'après envoi réussi — un échec temporaire de Resend ou l'absence
  /// de {@code CONTACT_EMAIL} laisse les erreurs disponibles pour le prochain cycle.
  @Scheduled(cron = "0 */15 * * * *")
  public void verifier() {
    long curseur = lireCurseur();
    List<LogAdminService.EntreeLogAdmin> erreurs = logAdminService.lireErreursDepuis(curseur);
    if (erreurs.isEmpty()) return;

    Optional<String> email = emailAlerteService.obtenirDestinataireAlerte();
    if (email.isEmpty()) {
      log.warn(
          "alerteLogsAdmin: aucun destinataire d'alerte disponible — {} erreur(s) en attente,"
              + " curseur non avancé",
          erreurs.size());
      return;
    }

    boolean envoye = emailAlerteService.envoyerAlerteLogsAdmin(email.get(), erreurs);
    if (!envoye) {
      log.warn(
          "alerteLogsAdmin: échec d'envoi — {} erreur(s) en attente, curseur non avancé",
          erreurs.size());
      return;
    }

    long nouveauCurseur = erreurs.get(erreurs.size() - 1).ts();
    configService.sauvegarderConfig(CLE_CURSEUR, String.valueOf(nouveauCurseur));
  }

  private long lireCurseur() {
    return configService.lireConfig(CLE_CURSEUR).map(Long::parseLong).orElse(0L);
  }
}
