/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.admin;

import fr.strapontin.web.email.EmailService;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/// Watchdog surveille l'activité du module worker toutes les heures.
///
/// <p>Si aucun job réussi n'est enregistré depuis {@value #SEUIL_HEURES} heures,
/// un email d'alerte est envoyé à l'adresse retournée par
/// {@link EmailService#obtenirDestinataireAlerte()} (#907).
/// Une nouvelle alerte ne peut être envoyée qu'après {@value #SEUIL_HEURES} heures
/// depuis la dernière, pour éviter le spam en cas de panne prolongée.
///
/// @since 2.2.5
/// @author Kwaite
@Service
public class WatchdogWorkerService {

  private static final Logger log = LoggerFactory.getLogger(WatchdogWorkerService.class);

  static final int SEUIL_HEURES = 3;
  private static final Duration SEUIL_INACTIVITE = Duration.ofHours(SEUIL_HEURES);

  private final NamedParameterJdbcTemplate jdbc;
  private final EmailService emailService;

  private volatile Instant derniereAlerte;

  public WatchdogWorkerService(NamedParameterJdbcTemplate jdbc, EmailService emailService) {
    this.jdbc = jdbc;
    this.emailService = emailService;
  }

  /// Vérifie l'activité du worker toutes les heures et envoie une alerte si inactif.
  @Scheduled(cron = "0 0 * * * *")
  public void verifier() {
    Instant derniereExec = lireDerniereExecReussie();
    boolean inactif =
        derniereExec == null
            || Duration.between(derniereExec, Instant.now()).compareTo(SEUIL_INACTIVITE) > 0;

    if (!inactif) return;

    if (derniereAlerte != null && Instant.now().isBefore(derniereAlerte.plus(SEUIL_INACTIVITE))) {
      log.debug("watchdog: worker inactif, alerte déjà envoyée récemment — ignorée");
      return;
    }

    String detailIso = derniereExec != null ? derniereExec.toString() : null;
    log.warn("watchdog: worker inactif depuis {} — envoi alerte", detailIso);

    emailService
        .obtenirDestinataireAlerte()
        .ifPresentOrElse(
            email -> {
              emailService.envoyerAlerteWorkerInactif(email, detailIso);
              derniereAlerte = Instant.now();
            },
            () ->
                log.warn("watchdog: aucun destinataire d'alerte disponible — alerte non envoyée"));
  }

  private Instant lireDerniereExecReussie() {
    try {
      Timestamp ts =
          jdbc.queryForObject(
              "SELECT MAX(derniere_exec) FROM fetch_jobs WHERE erreur IS NULL OR erreur = ''",
              Map.of(),
              Timestamp.class);
      return ts != null ? ts.toInstant() : null;
    } catch (Exception e) {
      log.warn("watchdog: impossible de lire fetch_jobs — {}", e.getMessage());
      return null;
    }
  }
}
