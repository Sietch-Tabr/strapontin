/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import fr.strapontin.worker.persister.PersisterConcerts.ResultatPersistance;
import java.util.Properties;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.info.BuildProperties;

class ExecutionNotificationVersionTest {

  private NotificationVersionPushService service;
  private NotificationVersionEmailService email;
  private ExecutionNotificationVersion execution;

  @BeforeEach
  void setUp() {
    service = mock(NotificationVersionPushService.class);
    email = mock(NotificationVersionEmailService.class);
    final WorkerStatsService stats = mock(WorkerStatsService.class);
    // Le suivi des statistiques exécute le travail transmis, comme en production.
    doAnswer(
            invocation -> {
              final Supplier<ResultatPersistance> travail = invocation.getArgument(2);
              travail.get();
              return null;
            })
        .when(stats)
        .executerAvecStats(any(), eq("notifier:version"), any());
    final Properties proprietes = new Properties();
    proprietes.setProperty("version", "9.9.9");
    execution =
        new ExecutionNotificationVersion(service, email, stats, new BuildProperties(proprietes));
  }

  @Test
  void executer_versionTransmise_laNotifie() {
    execution.executer("3.0.0");

    verify(service).notifierVersion("3.0.0");
    verify(email).notifierVersion("3.0.0");
  }

  @Test
  void executer_versionAbsenteOuBlanche_notifieLaVersionCourante() {
    execution.executer(null);
    execution.executer("  ");

    verify(service, org.mockito.Mockito.times(2)).notifierVersion("9.9.9");
  }
}
