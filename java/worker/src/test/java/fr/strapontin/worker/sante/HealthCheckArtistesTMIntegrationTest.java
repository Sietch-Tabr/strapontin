/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker.sante;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import fr.strapontin.sources.ticketmaster.TicketmasterSource;
import fr.strapontin.sources.ticketmaster.TicketmasterSource.StatutIdTM;
import fr.strapontin.worker.AbstractWorkerIntegrationTest;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Vérification de vivacité des identifiants d'artistes suspects
 * ({@link HealthCheckArtistesTM#verifierVivacite()}) sur base réelle.
 *
 * @since 3.0.2
 * @author Kwaite
 */
class HealthCheckArtistesTMIntegrationTest extends AbstractWorkerIntegrationTest {

  @MockitoBean TicketmasterSource ticketmasterSource;

  @Autowired private NamedParameterJdbcTemplate jdbc;
  @Autowired private HealthCheckArtistesTM healthCheck;

  @BeforeEach
  void nettoyer() {
    jdbc.update("DELETE FROM artiste_sources", Map.of());
  }

  private void artisteSuspect(final String attractionId) {
    final UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO artistes (id, nom, nom_norm) VALUES (CAST(:id AS uuid), :nom, :nom)",
        Map.of("id", id.toString(), "nom", "artiste " + id));
    jdbc.update(
        "INSERT INTO artiste_sources (artiste_id, source, identifiant_externe,"
            + " nb_zero_consecutifs, suspect_depuis) VALUES (CAST(:id AS uuid), 'ticketmaster',"
            + " :attraction, 3, now())",
        Map.of("id", id.toString(), "attraction", attractionId));
  }

  private Map<String, Object> etat(final String attractionId) {
    return jdbc.queryForMap(
        "SELECT suspect_depuis IS NOT NULL AS suspect, vivacite_verifiee_le IS NOT NULL AS"
            + " verifie FROM artiste_sources WHERE identifiant_externe = :id",
        Map.of("id", attractionId));
  }

  @Test
  void verifierVivacite_identifiantVivant_leveLaSuspicionEtDateLaVerification() {
    artisteSuspect("K-VIVANT");
    when(ticketmasterSource.verifierAttraction("K-VIVANT")).thenReturn(StatutIdTM.VIVANT);

    healthCheck.verifierVivacite();

    assertFalse((Boolean) etat("K-VIVANT").get("suspect"));
    assertTrue((Boolean) etat("K-VIVANT").get("verifie"));
  }

  // Appel en échec : rien n'est conclu, ni suspicion levée ni date de vérification.
  @Test
  void verifierVivacite_verificationIndeterminee_laisseLArtisteSuspect() {
    artisteSuspect("K-INCONNU");
    when(ticketmasterSource.verifierAttraction("K-INCONNU")).thenReturn(StatutIdTM.INDETERMINE);

    healthCheck.verifierVivacite();

    assertTrue((Boolean) etat("K-INCONNU").get("suspect"));
    assertFalse((Boolean) etat("K-INCONNU").get("verifie"));
  }

  @Test
  void verifierVivacite_quotaEpuise_nAppellePlusTicketmaster() {
    artisteSuspect("K-QUOTA");
    when(ticketmasterSource.quotaEpuise()).thenReturn(true);

    healthCheck.verifierVivacite();

    verify(ticketmasterSource, never()).verifierAttraction(anyString());
  }
}
