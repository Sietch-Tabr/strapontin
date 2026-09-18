/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import fr.strapontin.web.ErreurMetier;
import fr.strapontin.web.email.EmailServiceAdmin;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * Tests unitaires pour {@link AdminRevocationService} (révocation différée du rôle admin,
 * dépendances mockées).
 *
 * @since 2.9.1
 * @author Kwaite
 */
class AdminRevocationServiceTest {

  private NamedParameterJdbcTemplate jdbc;
  private EmailServiceAdmin emailServiceAdmin;
  private LogAdminService logAdminService;
  private AdminRevocationService service;

  @BeforeEach
  void setUp() {
    jdbc = mock(NamedParameterJdbcTemplate.class);
    emailServiceAdmin = mock(EmailServiceAdmin.class);
    logAdminService = mock(LogAdminService.class);
    service = new AdminRevocationService(jdbc, emailServiceAdmin, logAdminService);
    service.setSelf(service);
  }

  @Test
  void initierRevocationAdmin_utilisateurInexistant_leveErreurMetier() {
    when(jdbc.queryForList(anyString(), anyMap())).thenReturn(List.of());

    assertThrows(ErreurMetier.class, () -> service.initierRevocationAdmin("inconnu", "admin-1"));
    verify(emailServiceAdmin, never()).envoyerPreavisRevocationAdmin(anyString(), anyString());
  }

  @Test
  void initierRevocationAdmin_pasAdmin_leveErreurMetier() {
    final Map<String, Object> ligne = new HashMap<>();
    ligne.put("email", "user@exemple.fr");
    ligne.put("role", "user");
    when(jdbc.queryForList(anyString(), anyMap())).thenReturn(List.of(ligne));

    assertThrows(ErreurMetier.class, () -> service.initierRevocationAdmin("user-1", "admin-1"));
    verify(emailServiceAdmin, never()).envoyerPreavisRevocationAdmin(anyString(), anyString());
  }

  @Test
  void initierRevocationAdmin_seulAdmin_leveErreurMetier409() {
    final Map<String, Object> ligne = new HashMap<>();
    ligne.put("email", "admin@exemple.fr");
    ligne.put("role", "admin");
    when(jdbc.queryForList(anyString(), anyMap())).thenReturn(List.of(ligne));
    when(jdbc.queryForObject(anyString(), anyMap(), eq(Integer.class))).thenReturn(1);

    final ErreurMetier ex =
        assertThrows(
            ErreurMetier.class, () -> service.initierRevocationAdmin("admin-1", "admin-2"));
    assertEquals(409, ex.getStatus());
    verify(emailServiceAdmin, never()).envoyerPreavisRevocationAdmin(anyString(), anyString());
  }

  @Test
  void initierRevocationAdmin_adminCible_initieRevocationEtEnvoieEmail() {
    final Map<String, Object> ligne = new HashMap<>();
    ligne.put("email", "admin@exemple.fr");
    ligne.put("role", "admin");
    when(jdbc.queryForList(anyString(), anyMap())).thenReturn(List.of(ligne));
    when(jdbc.queryForObject(anyString(), anyMap(), eq(Integer.class))).thenReturn(2);
    when(jdbc.update(anyString(), anyMap())).thenReturn(1);

    service.initierRevocationAdmin("admin-1", "admin-2");

    verify(jdbc).update(anyString(), anyMap());
    verify(emailServiceAdmin).envoyerPreavisRevocationAdmin(eq("admin@exemple.fr"), anyString());
    verify(logAdminService)
        .logAdmin(eq("warn"), eq("admin"), eq("révocation admin initiée"), anyMap());
  }

  // deux révocations concurrentes peuvent lever un conflit de sérialisation PostgreSQL
  // (isolation SERIALIZABLE) — la première tentative échoue, la seconde réussit.
  @Test
  void initierRevocationAdmin_conflitSerialisationPuisSucces_reessaieEtReussit() {
    final Map<String, Object> ligne = new HashMap<>();
    ligne.put("email", "admin@exemple.fr");
    ligne.put("role", "admin");
    when(jdbc.queryForList(anyString(), anyMap())).thenReturn(List.of(ligne));
    when(jdbc.queryForObject(anyString(), anyMap(), eq(Integer.class))).thenReturn(2);
    when(jdbc.update(anyString(), anyMap()))
        .thenThrow(new PessimisticLockingFailureException("conflit"))
        .thenReturn(1);

    service.initierRevocationAdmin("admin-1", "admin-2");

    verify(jdbc, times(2)).update(anyString(), anyMap());
    verify(emailServiceAdmin).envoyerPreavisRevocationAdmin(eq("admin@exemple.fr"), anyString());
  }

  @Test
  void initierRevocationAdmin_conflitSerialisationPersistant_leveApresMaxTentatives() {
    final Map<String, Object> ligne = new HashMap<>();
    ligne.put("email", "admin@exemple.fr");
    ligne.put("role", "admin");
    when(jdbc.queryForList(anyString(), anyMap())).thenReturn(List.of(ligne));
    when(jdbc.queryForObject(anyString(), anyMap(), eq(Integer.class))).thenReturn(2);
    when(jdbc.update(anyString(), anyMap()))
        .thenThrow(new PessimisticLockingFailureException("conflit"));

    assertThrows(
        PessimisticLockingFailureException.class,
        () -> service.initierRevocationAdmin("admin-1", "admin-2"));
    verify(jdbc, times(3)).update(anyString(), anyMap());
  }

  @Test
  void annulerRevocationAdmin_revocationExistante_annule() {
    when(jdbc.update(anyString(), anyMap())).thenReturn(1);

    service.annulerRevocationAdmin("admin-1", "admin-2");

    verify(logAdminService)
        .logAdmin(eq("info"), eq("admin"), eq("révocation admin annulée"), anyMap());
  }

  @Test
  void annulerRevocationAdmin_aucuneRevocation_leveErreurMetier() {
    when(jdbc.update(anyString(), anyMap())).thenReturn(0);

    assertThrows(ErreurMetier.class, () -> service.annulerRevocationAdmin("admin-1", "admin-2"));
  }
}
