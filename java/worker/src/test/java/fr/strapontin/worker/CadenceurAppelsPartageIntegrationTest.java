/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker;

import static org.junit.jupiter.api.Assertions.assertTrue;

import fr.strapontin.core.CadenceurAppels;
import fr.strapontin.core.CadenceurAppelsPartage;
import fr.strapontin.sources.ticketmaster.TicketmasterSource;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Tests d'intégration de {@link CadenceurAppelsPartage} — vérifie la coordination via une vraie
 * base Postgres, y compris entre deux instances distinctes simulant deux processus indépendants
 * (web et worker) partageant la même clé.
 *
 * @since 2.8.1
 * @author Kwaite
 */
class CadenceurAppelsPartageIntegrationTest extends AbstractWorkerIntegrationTest {

  private static final String CLE_TEST = "cadenceur-test";

  // Le bean réel ticketmasterSource échoue au démarrage sans TICKETMASTER_API_KEY en base — ce
  // test construit ses propres instances de CadenceurAppelsPartage indépendamment de ce bean, le
  // mocker évite juste l'échec de contexte (même motif que WorkerFetchServiceIntegrationTest).
  @MockitoBean private TicketmasterSource ticketmasterSourceIgnore;

  @Autowired private NamedParameterJdbcTemplate jdbc;

  @BeforeEach
  void reinitialiserLigne() {
    jdbc.update(
        """
        INSERT INTO cadences_appels_externes (cle, prochain_appel_autorise) VALUES (:cle, now())
        ON CONFLICT (cle) DO UPDATE SET prochain_appel_autorise = now()
        """,
        Map.of("cle", CLE_TEST));
  }

  @Test
  void attendre_deuxAppelsSuccessifs_respecteLeDelaiMinimalEntreLesDeux() {
    final CadenceurAppels cadenceur = new CadenceurAppelsPartage(jdbc, CLE_TEST, 200);

    cadenceur.attendre();
    final long debut = System.currentTimeMillis();
    cadenceur.attendre();
    final long ecoule = System.currentTimeMillis() - debut;

    assertTrue(ecoule >= 180, "écart observé=" + ecoule + "ms, attendu ~200ms");
  }

  // La coordination doit fonctionner entre deux instances distinctes partageant la même clé —
  // c'est le scénario réel (une instance côté web, une côté worker, deux processus indépendants
  // ne pouvant pas se coordonner en mémoire) que ce cadenceur existe pour résoudre.
  @Test
  void attendre_deuxInstancesDistinctesMemeCle_respectentLeDelaiMinimalEntreLesDeux() {
    final CadenceurAppels cadenceurA = new CadenceurAppelsPartage(jdbc, CLE_TEST, 200);
    final CadenceurAppels cadenceurB = new CadenceurAppelsPartage(jdbc, CLE_TEST, 200);

    cadenceurA.attendre();
    final long debut = System.currentTimeMillis();
    cadenceurB.attendre();
    final long ecoule = System.currentTimeMillis() - debut;

    assertTrue(ecoule >= 180, "écart observé=" + ecoule + "ms, attendu ~200ms");
  }

  // Contrairement au cadenceur en mémoire, chaque appel a un coût incompressible d'aller-retour
  // JDBC — la marge (300ms d'écart naturel pour un délai minimal de 50ms) et le seuil de tolérance
  // absorbent ce coût sans reproduire une attente artificielle de type délai-plein.
  @Test
  void attendre_appelsEspacesNaturellement_neBloquePasAuDelaDuNecessaire()
      throws InterruptedException {
    final CadenceurAppels cadenceur = new CadenceurAppelsPartage(jdbc, CLE_TEST, 50);

    cadenceur.attendre();
    Thread.sleep(300);
    final long debut = System.currentTimeMillis();
    cadenceur.attendre();
    final long ecoule = System.currentTimeMillis() - debut;

    assertTrue(
        ecoule < 150,
        "aucune attente artificielle attendue au-delà du round-trip, observé=" + ecoule + "ms");
  }

  @Test
  void attendre_premierAppelApresInactivite_neBloquePas() {
    final CadenceurAppels cadenceur = new CadenceurAppelsPartage(jdbc, CLE_TEST, 1000);

    final long debut = System.currentTimeMillis();
    cadenceur.attendre();
    final long ecoule = System.currentTimeMillis() - debut;

    assertTrue(ecoule < 500, "aucune attente attendue à vide, observé=" + ecoule + "ms");
  }
}
