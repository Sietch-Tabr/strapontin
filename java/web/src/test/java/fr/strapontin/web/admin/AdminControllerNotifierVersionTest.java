/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import fr.strapontin.web.email.EmailServiceAdmin;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.info.BuildProperties;
import org.springframework.http.ResponseEntity;

/**
 * Tests unitaires pour {@link SystemeAdminController#notifierNouvelleVersion} et {@link
 * SystemeAdminController#lireInfo} (#1231).
 *
 * @since 3.0.0
 * @author Kwaite
 */
class AdminControllerNotifierVersionTest {

  private WorkerProxyService workerProxyService;
  private BuildProperties buildProperties;
  private SystemeAdminController controller;

  @BeforeEach
  void setUp() {
    workerProxyService = mock(WorkerProxyService.class);
    buildProperties = mock(BuildProperties.class);
    controller =
        new SystemeAdminController(
            mock(AdminService.class),
            mock(ConfigAdminService.class),
            mock(JobsAdminService.class),
            mock(BanniereService.class),
            workerProxyService,
            mock(EmailServiceAdmin.class),
            mock(VapidService.class),
            buildProperties);
  }

  @Test
  void lireInfo_exposeLaVersionCourante() {
    when(buildProperties.getVersion()).thenReturn("3.0.0");

    final ResponseEntity<Map<String, Object>> rep = controller.lireInfo();

    assertEquals("3.0.0", rep.getBody().get("version"));
  }

  @Test
  void notifierNouvelleVersion_succes_retourne200AvecLaVersion() {
    when(buildProperties.getVersion()).thenReturn("3.0.0");
    when(workerProxyService.notifierNouvelleVersion("3.0.0"))
        .thenReturn(new ResultatRelance(true, null, 200));

    final ResponseEntity<Map<String, Object>> rep = controller.notifierNouvelleVersion();

    assertEquals(200, rep.getStatusCode().value());
    assertEquals("3.0.0", rep.getBody().get("version"));
  }

  @Test
  void notifierNouvelleVersion_workerInaccessible_retourne503() {
    when(buildProperties.getVersion()).thenReturn("3.0.0");
    when(workerProxyService.notifierNouvelleVersion("3.0.0"))
        .thenReturn(new ResultatRelance(false, "Worker inaccessible.", 503));

    final ResponseEntity<Map<String, Object>> rep = controller.notifierNouvelleVersion();

    assertEquals(503, rep.getStatusCode().value());
  }
}
