/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.admin;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import fr.strapontin.web.email.EmailAlerteService;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/// Tests unitaires pour {@link WatchdogWorkerService}.
///
/// <p>Couvre la résolution du destinataire via {@link EmailAlerteService#obtenirDestinataireAlerte()}
/// (plus de lecture directe de CONTACT_EMAIL).
///
/// @since 2.7.0
/// @author Kwaite
class WatchdogWorkerServiceTest {

  private NamedParameterJdbcTemplate jdbc;
  private EmailAlerteService emailAlerteService;
  private WatchdogWorkerService service;

  @BeforeEach
  void setUp() {
    jdbc = mock(NamedParameterJdbcTemplate.class);
    emailAlerteService = mock(EmailAlerteService.class);
    service = new WatchdogWorkerService(jdbc, emailAlerteService);
  }

  @Test
  void verifier_workerInactifEtDestinataireDisponible_envoieAlerte() {
    when(jdbc.queryForObject(anyString(), anyMap(), eq(Timestamp.class))).thenReturn(null);
    when(emailAlerteService.obtenirDestinataireAlerte()).thenReturn(Optional.of("alerte@exemple.fr"));

    service.verifier();

    verify(emailAlerteService).envoyerAlerteWorkerInactif(eq("alerte@exemple.fr"), any());
  }

  @Test
  void verifier_aucunDestinataireDisponible_neFaitRien() {
    when(jdbc.queryForObject(anyString(), anyMap(), eq(Timestamp.class))).thenReturn(null);
    when(emailAlerteService.obtenirDestinataireAlerte()).thenReturn(Optional.empty());

    service.verifier();

    verify(emailAlerteService, never()).envoyerAlerteWorkerInactif(anyString(), any());
  }

  @Test
  void verifier_workerActifRecemment_neConsultePasLeDestinataire() {
    Timestamp recent = Timestamp.from(Instant.now().minus(1, ChronoUnit.HOURS));
    when(jdbc.queryForObject(anyString(), anyMap(), eq(Timestamp.class))).thenReturn(recent);

    service.verifier();

    verify(emailAlerteService, never()).obtenirDestinataireAlerte();
  }
}
