/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker.sante;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import fr.strapontin.sources.eventim.EventimSource;
import fr.strapontin.sources.ticketmaster.TicketmasterSource;
import fr.strapontin.worker.AbstractWorkerIntegrationTest;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Vérification des identifiants Eventim enregistrés, sur base réelle.
 *
 * @since 3.1.1
 * @author Kwaite
 */
class VerificationIdentifiantsEventimIntegrationTest extends AbstractWorkerIntegrationTest {

  @MockitoBean TicketmasterSource ticketmasterSource;
  @MockitoBean EventimSource eventimSource;

  @Autowired private NamedParameterJdbcTemplate jdbc;
  @Autowired private VerificationIdentifiantsEventim verification;

  @BeforeEach
  void preparer() {
    jdbc.update("DELETE FROM signalements_matching", Map.of());
    jdbc.update("DELETE FROM artiste_sources", Map.of());
    jdbc.update("DELETE FROM artistes", Map.of());
    jdbc.update(
        "INSERT INTO artistes (id, nom, nom_norm) VALUES"
            + " (CAST('00000000-0000-0000-0000-0000000000f1' AS uuid), 'Furies', 'furies')",
        Map.of());
    jdbc.update(
        "INSERT INTO artiste_sources (artiste_id, source, identifiant_externe) VALUES"
            + " (CAST('00000000-0000-0000-0000-0000000000f1' AS uuid), 'eventim', '810294')",
        Map.of());
  }

  @Test
  void verifier_identifiantDUnAutreArtiste_signaleEtDateLaVerification() {
    when(eventimSource.attractionDUnAutreArtiste(eq("Furies"), eq("810294"), anyBoolean(), any()))
        .thenReturn(true);

    assertEquals(1, verification.verifier());

    assertEquals(
        List.of("identifiant_externe_autre_artiste"),
        jdbc.queryForList(
            "SELECT type FROM signalements_matching WHERE resolu_le IS NULL",
            Map.of(),
            String.class));
    assertNotNull(
        jdbc.queryForObject(
            "SELECT verifie_le FROM artiste_sources WHERE identifiant_externe = '810294'",
            Map.of(),
            OffsetDateTime.class));
  }

  // La règle d'identité est celle du fetch : confirmée par Deezer ou Wikidata, ou non.
  @Test
  void verifier_artisteAvecIdentifiantDeezer_transmetUneIdentiteConfirmee() {
    jdbc.update("UPDATE artistes SET deezer_id = '12345' WHERE nom_norm = 'furies'", Map.of());
    when(eventimSource.attractionDUnAutreArtiste(any(), any(), eq(true), any())).thenReturn(true);

    assertEquals(1, verification.verifier());
  }

  @Test
  void verifier_artisteSansIdentifiantExterne_transmetUneIdentiteNonConfirmee() {
    when(eventimSource.attractionDUnAutreArtiste(any(), any(), eq(false), any())).thenReturn(false);

    assertEquals(0, verification.verifier());
    verify(eventimSource).attractionDUnAutreArtiste(eq("Furies"), eq("810294"), eq(false), any());
  }

  @Test
  void verifier_signalementOuvertPuisIdentifiantConforme_leFerme() {
    when(eventimSource.attractionDUnAutreArtiste(any(), any(), anyBoolean(), any()))
        .thenReturn(true, false);
    verification.verifier();

    assertEquals(0, verification.verifier());

    assertEquals(
        0L,
        jdbc.queryForObject(
            "SELECT count(*) FROM signalements_matching WHERE resolu_le IS NULL",
            Map.of(),
            Long.class));
  }

  @Test
  void verifier_identifiantConforme_neSignaleRien() {
    when(eventimSource.attractionDUnAutreArtiste(any(), any(), anyBoolean(), any()))
        .thenReturn(false);

    assertEquals(0, verification.verifier());
  }
}
