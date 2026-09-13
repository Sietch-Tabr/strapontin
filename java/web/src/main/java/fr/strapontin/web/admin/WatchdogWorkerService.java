/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.admin;

import fr.strapontin.web.email.EmailAlerteService;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Watchdog surveille l'activité du worker toutes les heures — alerte
 * email ({@link EmailAlerteService#obtenirDestinataireAlerte}) si
 * aucun job réussi depuis {@value #SEUIL_HEURES} heures, jamais avant
 * ce même délai depuis la dernière alerte (anti-spam).
 * @since 2.2.5
 * @author Kwaite
 */
@Service
public class WatchdogWorkerService {

  private static final Logger LOG = LoggerFactory.getLogger(WatchdogWorkerService.class);

  private static final int SEUIL_HEURES = 3;
  private static final Duration SEUIL_INACTIVITE = Duration.ofHours(SEUIL_HEURES);

  private final NamedParameterJdbcTemplate jdbc;
  private final EmailAlerteService emailAlerteService;

  // Lu/écrit uniquement depuis verifier(), toujours invoquée séquentiellement
  // par le TaskScheduler Spring par défaut (pas de concurrence, volatile
  // inutile).
  private Instant derniereAlerte;

  /**
   * @param jdbc accès JDBC
   * @param emailAlerteService résolution du destinataire de l'alerte
   */
  public WatchdogWorkerService(
      final NamedParameterJdbcTemplate jdbc, final EmailAlerteService emailAlerteService) {
    this.jdbc = jdbc;
    this.emailAlerteService = emailAlerteService;
  }

  /**
   * Vérifie l'activité du worker toutes les heures et envoie une alerte si
   * inactif.
   */
  @Scheduled(cron = "0 0 * * * *")
  public void verifier() {
    final Instant derniereExec = lireDerniereExecReussie();
    final boolean inactif =
        derniereExec == null
            || Duration.between(derniereExec, Instant.now()).compareTo(SEUIL_INACTIVITE) > 0;

    if (!inactif) {
      return;
    }

    if (derniereAlerte != null && Instant.now().isBefore(derniereAlerte.plus(SEUIL_INACTIVITE))) {
      LOG.debug("watchdog: worker inactif, alerte déjà envoyée récemment — ignorée");
      return;
    }

    final String detailIso = derniereExec != null ? derniereExec.toString() : null;
    LOG.warn("watchdog: worker inactif depuis {} — envoi alerte", detailIso);

    emailAlerteService
        .obtenirDestinataireAlerte()
        .ifPresentOrElse(
            email -> {
              emailAlerteService.envoyerAlerteWorkerInactif(email, detailIso);
              derniereAlerte = Instant.now();
            },
            () ->
                LOG.warn("watchdog: aucun destinataire d'alerte disponible — alerte non envoyée"));
  }

  private Instant lireDerniereExecReussie() {
    try {
      return jdbc.queryForObject(
          "SELECT MAX(derniere_exec) FROM fetch_jobs WHERE erreur IS NULL OR erreur = ''",
          Map.of(),
          Instant.class);
    } catch (Exception e) {
      LOG.warn("watchdog: impossible de lire fetch_jobs — {}", e.getMessage());
      return null;
    }
  }
}
