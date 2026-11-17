/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import fr.strapontin.core.JournalAdmin;
import fr.strapontin.sources.ticketmaster.TicketmasterSource;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * Écriture partagée dans {@code logs_admin} ({@link JournalAdmin}) sur base réelle.
 *
 * @since 3.2.0
 * @author Kwaite
 */
class JournalAdminIntegrationTest extends AbstractWorkerIntegrationTest {

  @MockitoBean TicketmasterSource ticketmasterSource;

  @Autowired private NamedParameterJdbcTemplate jdbc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private PlatformTransactionManager transactions;

  private JournalAdmin journal;

  @BeforeEach
  void preparer() {
    jdbc.update("DELETE FROM logs_admin", Map.of());
    journal = new JournalAdmin(jdbc, objectMapper);
  }

  private long lignes() {
    return jdbc.queryForObject("SELECT count(*) FROM logs_admin", Map.of(), Long.class);
  }

  @Test
  void ecrire_ecritNiveauSourceMessageEtMetaJsonb() {
    journal.ecrire("error", "web", "un message", Map.of("cle", "valeur"));
    final Map<String, Object> ligne =
        jdbc.queryForMap(
            "SELECT niveau, source, message, meta->>'cle' AS cle FROM logs_admin", Map.of());
    assertEquals("error", ligne.get("niveau"));
    assertEquals("web", ligne.get("source"));
    assertEquals("un message", ligne.get("message"));
    assertEquals("valeur", ligne.get("cle"));
  }

  @Test
  void ecrire_sansMeta_ecritUnNull() {
    journal.ecrire("info", "web", "sans meta", null);
    assertNull(jdbc.queryForObject("SELECT meta::text FROM logs_admin", Map.of(), String.class));
  }

  @Test
  void ecrire_avertissementIdentiqueRepete_resteTousEcrit() {
    journal.ecrire("warn", "web", "répété", null);
    journal.ecrire("warn", "web", "répété", null);
    assertEquals(2, lignes());
  }

  @Test
  void ecrireSansRepetition_avertissementIdentique_nEstEcritQuUneFois() {
    journal.ecrireSansRepetition("warn", "web", "répété", null);
    journal.ecrireSansRepetition("warn", "web", "répété", null);
    assertEquals(1, lignes());
  }

  @Test
  void ecrireSansRepetition_autreMessageOuAutreSource_estEcrit() {
    journal.ecrireSansRepetition("warn", "web", "un", null);
    journal.ecrireSansRepetition("warn", "web", "deux", null);
    journal.ecrireSansRepetition("warn", "worker", "un", null);
    assertEquals(3, lignes());
  }

  @Test
  void ecrireSansRepetition_autresNiveauxIdentiques_sontTousEcrits() {
    journal.ecrireSansRepetition("info", "web", "début", null);
    journal.ecrireSansRepetition("info", "web", "début", null);
    journal.ecrireSansRepetition("error", "web", "échec", null);
    journal.ecrireSansRepetition("error", "web", "échec", null);
    assertEquals(4, lignes());
  }

  @Test
  void ecrireSansRepetition_avertissementVieuxDePlusDe24h_estEcritDeNouveau() {
    journal.ecrireSansRepetition("warn", "web", "ancien", null);
    jdbc.update("UPDATE logs_admin SET ts = now() - interval '25 hours'", Map.of());
    journal.ecrireSansRepetition("warn", "web", "ancien", null);
    assertEquals(2, lignes());
  }

  @Test
  void ecrireDansLaTransaction_partageLeSortDeLaTransaction() {
    final TransactionTemplate transaction = new TransactionTemplate(transactions);
    assertThrows(
        IllegalStateException.class,
        () ->
            transaction.executeWithoutResult(
                statut -> {
                  journal.ecrireDansLaTransaction("warn", "admin", "annulé", Map.of());
                  throw new IllegalStateException("échec après l'écriture");
                }));
    assertEquals(0, lignes());
  }

  @Test
  void ecrireDansLaTransaction_transactionValidee_laLigneEstConservee() {
    new TransactionTemplate(transactions)
        .executeWithoutResult(
            statut -> journal.ecrireDansLaTransaction("warn", "admin", "conservé", Map.of()));
    assertEquals(1, lignes());
  }
}
