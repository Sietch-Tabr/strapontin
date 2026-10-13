/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import fr.strapontin.sources.TicketmasterSource;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Avertissement unique par perte du dernier canal de notification, réarmé
 * quand un canal redevient joignable (PostgreSQL réel).
 * @since 3.0.3
 * @author Kwaite
 */
class AvertissementCanalPerduServiceIntegrationTest extends AbstractWorkerIntegrationTest {

  // Le bean réel échoue au démarrage sans TICKETMASTER_API_KEY en base.
  @MockitoBean private TicketmasterSource ticketmasterSourceIgnore;
  @MockitoBean private EmailCompteService emailCompteService;

  @Autowired private NamedParameterJdbcTemplate jdbc;
  @Autowired private AvertissementCanalPerduService service;

  @BeforeEach
  void creerCompte() {
    jdbc.update(
        "INSERT INTO users (id, email, role, display_username)"
            + " VALUES ('u1', 'u1@test.fr', CAST('user' AS role_utilisateur), 'u1')",
        Map.of());
    jdbc.update(
        """
        INSERT INTO user_config (user_id, frequence_notifications, notif_artistes, notif_salles,
                                 notif_artistes_push, notif_salles_push, dernier_appareil_retire_le,
                                 cause_dernier_retrait_appareil)
        VALUES ('u1', 'quotidien', false, false, true, false, now(), 'cause lisible')
        """,
        Map.of());
    when(emailCompteService.envoyerAvertissementCanalPerdu(anyString(), any())).thenReturn(true);
    when(emailCompteService.envoyerAvertissementPushJamaisActive(anyString())).thenReturn(true);
  }

  private void ajouterMatchEnAttente(final String age) {
    jdbc.update(
        "INSERT INTO artistes (id, nom, nom_norm) VALUES"
            + " (CAST('00000000-0000-0000-0000-0000000000a1' AS uuid), 'A', 'a')",
        Map.of());
    jdbc.update(
        "INSERT INTO evenements (id, artiste_norm, salle_norm, date, artiste_id) VALUES"
            + " (CAST('00000000-0000-0000-0000-0000000000e1' AS uuid), 'a', 'salle', CURRENT_DATE + 30,"
            + " CAST('00000000-0000-0000-0000-0000000000a1' AS uuid))",
        Map.of());
    jdbc.update(
        "INSERT INTO user_matches (user_id, concert_id, cree_le) VALUES"
            + " ('u1', CAST('00000000-0000-0000-0000-0000000000e1' AS uuid),"
            + " now() - CAST(:age AS interval))",
        Map.of("age", age));
  }

  private void abonnerAppareil() {
    jdbc.update(
        "INSERT INTO push_abonnements (user_id, endpoint, p256dh, auth)"
            + " VALUES ('u1', 'https://fcm.googleapis.com/x', 'p', 'a')",
        Map.of());
  }

  @Test
  void verifier_dernierAppareilRetire_avertitUneSeuleFois() {
    assertEquals(1, service.verifier());
    assertEquals(0, service.verifier());

    verify(emailCompteService, times(1)).envoyerAvertissementCanalPerdu(eq("u1@test.fr"), any());
  }

  @Test
  void verifier_canalRetrouvePuisReperdu_avertitDeNouveau() {
    service.verifier();
    abonnerAppareil();
    service.verifier();
    jdbc.update("DELETE FROM push_abonnements", Map.of());

    assertEquals(1, service.verifier());
    verify(emailCompteService, times(2)).envoyerAvertissementCanalPerdu(eq("u1@test.fr"), any());
  }

  @Test
  void verifier_emailActif_aucunAvertissement() {
    jdbc.update("UPDATE user_config SET notif_artistes = true", Map.of());

    assertEquals(0, service.verifier());
    verify(emailCompteService, never()).envoyerAvertissementCanalPerdu(anyString(), any());
  }

  @Test
  void verifier_dernierAppareilRetire_transmetLaCause() {
    service.verifier();

    verify(emailCompteService).envoyerAvertissementCanalPerdu("u1@test.fr", "cause lisible");
  }

  @Test
  void verifier_pushJamaisAbonneSansMatchEnAttente_aucunAvertissement() {
    jdbc.update("UPDATE user_config SET dernier_appareil_retire_le = NULL", Map.of());

    assertEquals(0, service.verifier());
  }

  @Test
  void verifier_pushJamaisAbonneAvecMatchRecent_aucunAvertissement() {
    jdbc.update("UPDATE user_config SET dernier_appareil_retire_le = NULL", Map.of());
    ajouterMatchEnAttente("1 hour");

    assertEquals(0, service.verifier());
  }

  @Test
  void verifier_pushJamaisAbonneAvecMatchEnAttente_avertitDuPushJamaisActive() {
    jdbc.update("UPDATE user_config SET dernier_appareil_retire_le = NULL", Map.of());
    ajouterMatchEnAttente("2 days");

    assertEquals(1, service.verifier());
    verify(emailCompteService).envoyerAvertissementPushJamaisActive("u1@test.fr");
    verify(emailCompteService, never()).envoyerAvertissementCanalPerdu(anyString(), any());
  }

  @Test
  void verifier_envoiEchoue_retenteAuCycleSuivant() {
    when(emailCompteService.envoyerAvertissementCanalPerdu(anyString(), any())).thenReturn(false);
    assertEquals(0, service.verifier());

    when(emailCompteService.envoyerAvertissementCanalPerdu(anyString(), any())).thenReturn(true);
    assertEquals(1, service.verifier());
  }
}
