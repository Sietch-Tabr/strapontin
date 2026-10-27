/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.securite;

import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import fr.strapontin.web.email.EmailServiceSecurite;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * Tests unitaires pour {@link AlerteActiviteUtilisateurService}.
 *
 * @since 3.0.0
 * @author Kwaite
 */
class AlerteActiviteUtilisateurServiceTest {

  private NamedParameterJdbcTemplate jdbc;
  private EmailServiceSecurite emailServiceSecurite;
  private AlerteActiviteUtilisateurService service;

  @BeforeEach
  void setUp() {
    jdbc = mock(NamedParameterJdbcTemplate.class);
    emailServiceSecurite = mock(EmailServiceSecurite.class);
    service = new AlerteActiviteUtilisateurService(jdbc, emailServiceSecurite);
  }

  @Test
  void notifierNouvelAppareil_emailPresent_envoieLAlerte() {
    service.notifierNouvelAppareil("alice@test.fr", "Mozilla/5.0", "navigateur");

    verify(emailServiceSecurite)
        .envoyerAlerteNouvelAppareil("alice@test.fr", "Mozilla/5.0", "Appareil inconnu");
  }

  @Test
  void notifierNouvelAppareil_emailAbsent_neFaitRien() {
    service.notifierNouvelAppareil(null, "Mozilla/5.0", null);

    verify(emailServiceSecurite, never())
        .envoyerAlerteNouvelAppareil(anyString(), anyString(), anyString());
  }

  @Test
  void notifierEchecsConnexionRepetes_identifiantConnu_envoieLAlerteAuBonEmail() {
    when(jdbc.queryForList(anyString(), anyMap()))
        .thenReturn(List.of(Map.of("email", "alice@test.fr")));

    service.notifierEchecsConnexionRepetes("alice");

    verify(emailServiceSecurite).envoyerAlerteEchecsConnexion("alice@test.fr");
  }

  @Test
  void notifierEchecsConnexionRepetes_identifiantInconnu_neFaitRien() {
    when(jdbc.queryForList(anyString(), anyMap())).thenReturn(List.of());

    service.notifierEchecsConnexionRepetes("inconnu");

    verify(emailServiceSecurite, never()).envoyerAlerteEchecsConnexion(anyString());
  }
}
