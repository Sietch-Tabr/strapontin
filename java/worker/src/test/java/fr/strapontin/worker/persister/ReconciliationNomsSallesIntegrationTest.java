/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker.persister;

import static org.junit.jupiter.api.Assertions.assertEquals;

import fr.strapontin.sources.ticketmaster.TicketmasterSource;
import fr.strapontin.worker.AbstractWorkerIntegrationTest;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Renommage de salles par {@link ReconciliationNomsSalles#absorberRenommagesSalles} sur base
 * réelle : source de référence et stabilité du nom d'un cycle à l'autre.
 *
 * @since 3.0.2
 * @author Kwaite
 */
class ReconciliationNomsSallesIntegrationTest extends AbstractWorkerIntegrationTest {

  // Le bean réel échoue au démarrage sans TICKETMASTER_API_KEY en base.
  @MockitoBean TicketmasterSource ticketmasterSource;

  @Autowired private NamedParameterJdbcTemplate jdbc;

  private ReconciliationNomsSalles reconciliation;
  private UUID salleId;

  @BeforeEach
  void setUp() {
    reconciliation = new ReconciliationNomsSalles(jdbc);
    salleId = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO salles (id, salle_cle, nom, nom_norm, dept)"
            + " VALUES (CAST(:id AS uuid), 'l olympia', 'L''Olympia', 'l olympia', '75')",
        Map.of("id", salleId.toString()));
  }

  private void mapper(final String source, final String identifiant) {
    jdbc.update(
        "INSERT INTO salle_sources (salle_id, source, identifiant_externe)"
            + " VALUES (CAST(:id AS uuid), :source, :identifiant)",
        Map.of("id", salleId.toString(), "source", source, "identifiant", identifiant));
  }

  private void absorber(final String source, final String identifiant, final String nom) {
    final ValeurConcert concert =
        FabriqueValeurConcert.nouveau()
            .artiste("Stromae")
            .artisteNorm("stromae")
            .date(LocalDate.now().plusMonths(2))
            .salle(nom)
            .salleNorm(nom.toLowerCase())
            .ville("Paris")
            .dept("75")
            .source(source)
            .salleSourceId(identifiant)
            .construire();
    reconciliation.absorberRenommagesSalles(
        List.of(concert), Map.of(identifiant, salleId), Map.of());
  }

  private String nom() {
    return jdbc.queryForObject(
        "SELECT nom FROM salles WHERE id = CAST(:id AS uuid)",
        Map.of("id", salleId.toString()),
        String.class);
  }

  @Test
  void absorberRenommagesSalles_mappingUniqueRenommeEnSource_appliqueLeNouveauNom() {
    mapper("ticketmaster", "ticketmaster:olympia");

    absorber("ticketmaster", "ticketmaster:olympia", "L'Olympia Bruno Coquatrix");

    assertEquals("L'Olympia Bruno Coquatrix", nom());
    assertEquals(
        "l olympia bruno coquatrix",
        jdbc.queryForObject(
            "SELECT nom_norm FROM salles WHERE id = CAST(:id AS uuid)",
            Map.of("id", salleId.toString()),
            String.class));
  }

  @Test
  void absorberRenommagesSalles_mappingUniqueDeuxGraphiesDUnMemeNom_gardeLaMeilleureEntreLesLots() {
    mapper("ticketmaster", "ticketmaster:liberte");
    jdbc.update(
        "UPDATE salles SET nom = 'Le Liberté Rennes' WHERE id = CAST(:id AS uuid)",
        Map.of("id", salleId.toString()));

    absorber("ticketmaster", "ticketmaster:liberte", "Le Liberte - Rennes");
    assertEquals("Le Liberté Rennes", nom());
    absorber("ticketmaster", "ticketmaster:liberte", "Le Liberté Rennes");
    assertEquals("Le Liberté Rennes", nom());
  }

  private void observationTm(final String libelle, final int joursSansVue) {
    final UUID evenementId =
        jdbc.queryForObject(
            "INSERT INTO evenements (artiste_norm, date, heure, salle_norm, salle_id)"
                + " VALUES (:norm, CURRENT_DATE + 30, '20:00', 'salle', CAST(:salle AS uuid))"
                + " RETURNING id",
            Map.of("norm", "artiste " + UUID.randomUUID(), "salle", salleId.toString()),
            UUID.class);
    jdbc.update(
        "INSERT INTO observations (evenement_id, source, url, artiste_brut, salle_brut,"
            + " updated_at) VALUES (CAST(:id AS uuid), 'ticketmaster', :url, 'a', :libelle,"
            + " now() - make_interval(days => :jours))",
        Map.of(
            "id",
            evenementId.toString(),
            "url",
            "https://tm/" + evenementId,
            "libelle",
            libelle,
            "jours",
            joursSansVue));
  }

  // Zénith de Lille : Ticketmaster publie deux libellés d'un même lieu selon la requête.
  @Test
  void absorberRenommagesSalles_ancienLibelleToujoursPublie_neRenommePasEnAlternance() {
    mapper("ticketmaster", "ticketmaster:zenith-lille");
    jdbc.update(
        "UPDATE salles SET nom = 'Zenith de Lille' WHERE id = CAST(:id AS uuid)",
        Map.of("id", salleId.toString()));
    observationTm("Zenith de Lille", 0);

    absorber("ticketmaster", "ticketmaster:zenith-lille", "Zenith Arena de Lille");
    assertEquals("Zenith de Lille", nom());
  }

  @Test
  void absorberRenommagesSalles_ancienLibelleNePublieePlusDepuisTroisJours_renomme() {
    mapper("ticketmaster", "ticketmaster:zenith-lille");
    jdbc.update(
        "UPDATE salles SET nom = 'Zenith de Lille' WHERE id = CAST(:id AS uuid)",
        Map.of("id", salleId.toString()));
    observationTm("Zenith de Lille", 10);

    absorber("ticketmaster", "ticketmaster:zenith-lille", "Zenith Arena de Lille");
    assertEquals("Zenith Arena de Lille", nom());
  }

  @Test
  void absorberRenommagesSalles_mappingUniqueGraphieStrictementMeilleure_appliqueLaGraphie() {
    mapper("ticketmaster", "ticketmaster:zenith");
    jdbc.update(
        "UPDATE salles SET nom = 'Zenith Toulouse Metropole' WHERE id = CAST(:id AS uuid)",
        Map.of("id", salleId.toString()));

    absorber("ticketmaster", "ticketmaster:zenith", "Zénith Toulouse Métropole");

    assertEquals("Zénith Toulouse Métropole", nom());
  }

  // Théâtre de l'Atelier : Ticketmaster écrit « L'Atelier », Eventim « lAtelier » ; la salle
  // gardait un nom le matin et l'autre dix minutes plus tard.
  @Test
  void
      absorberRenommagesSalles_graphieTicketmasterSeulementDifferenteParLApostrophe_neRenommePas() {
    mapper("ticketmaster", "ticketmaster:atelier");
    jdbc.update(
        "UPDATE salles SET nom = 'Théâtre de lAtelier' WHERE id = CAST(:id AS uuid)",
        Map.of("id", salleId.toString()));

    absorber("ticketmaster", "ticketmaster:atelier", "Theatre de L'Atelier");
    assertEquals("Théâtre de lAtelier", nom());
    absorber("ticketmaster", "ticketmaster:atelier", "Theatre de L'Atelier");
    assertEquals("Théâtre de lAtelier", nom());
  }

  @Test
  void absorberRenommagesSalles_nomEventimDUneSalleAvecMappingTicketmaster_neRenommePas() {
    mapper("ticketmaster", "ticketmaster:olympia");
    mapper("eventim", "eventim:internal:lolympia");

    absorber("eventim", "eventim:internal:lolympia", "LOlympia");

    assertEquals("L'Olympia", nom());
  }

  @Test
  void absorberRenommagesSalles_deuxMappingsMemeSourceScoreEgal_nomStableEntreLesCycles() {
    mapper("eventim", "eventim:internal:salle pleyel");
    mapper("eventim", "eventim:internal:salle pleyel paris 08");
    jdbc.update(
        "UPDATE salles SET nom = 'Salle Pleyel' WHERE id = CAST(:id AS uuid)",
        Map.of("id", salleId.toString()));

    absorber("eventim", "eventim:internal:salle pleyel paris 08", "Salle Pleyel Paris 08");
    assertEquals("Salle Pleyel", nom());
    absorber("eventim", "eventim:internal:salle pleyel", "Salle Pleyel");
    assertEquals("Salle Pleyel", nom());
  }

  // Concert Ticketmaster fusionné avec celui d'Eventim : le nom affiché est celui d'Eventim, le
  // nom publié reste celui de Ticketmaster, seul retenu pour renommer la salle.
  @Test
  void
      absorberRenommagesSalles_concertFusionneDontLeNomAffichéVientDUneAutreSource_utiliseLeNomPublie() {
    mapper("ticketmaster", "ticketmaster:olympia");
    final ValeurConcert fusionne =
        FabriqueValeurConcert.nouveau()
            .artiste("Stromae")
            .artisteNorm("stromae")
            .date(LocalDate.now().plusMonths(2))
            .salle("L'Olympia Bruno Coquatrix Paris")
            .sallePubliee("L'Olympia")
            .salleNorm("l olympia bruno coquatrix paris")
            .ville("Paris")
            .dept("75")
            .source("ticketmaster")
            .salleSourceId("ticketmaster:olympia")
            .construire();

    reconciliation.absorberRenommagesSalles(
        List.of(fusionne), Map.of("ticketmaster:olympia", salleId), Map.of());

    assertEquals("L'Olympia", nom());
  }
}
