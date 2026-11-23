/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker.sante;

import static org.junit.jupiter.api.Assertions.assertEquals;

import fr.strapontin.sources.ticketmaster.TicketmasterSource;
import fr.strapontin.worker.AbstractWorkerIntegrationTest;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Tests d'intégration de {@link SignalementMatchingService} avec PostgreSQL réel (Testcontainers)
 * — vérifie le débounce par index unique partiel, non exerçable avec un jdbc mocké.
 *
 * @since 2.9.0
 * @author Kwaite
 */
class SignalementMatchingServiceIntegrationTest extends AbstractWorkerIntegrationTest {

  // TICKETMASTER_API_KEY n'est pas seedée en base de test — sans ce mock, la construction du
  // contexte Spring échoue dès qu'une autre classe du contexte dépend de TicketmasterSource
  // (WorkerFetchService), même sans rapport avec ce test.
  @MockitoBean TicketmasterSource ticketmasterSource;

  @Autowired private SignalementMatchingService service;
  @Autowired private NamedParameterJdbcTemplate jdbc;

  @Test
  void signaler_memeTypeEtEntiteDeuxFois_neCreeQuUneLigneActive() {
    final UUID entiteId = UUID.randomUUID();

    service.signaler("collision_wikidata_artiste", "artiste", entiteId, "haute", null);
    service.signaler("collision_wikidata_artiste", "artiste", entiteId, "haute", null);

    final Long nb =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM signalements_matching WHERE entite_id = CAST(:id AS uuid)",
            Map.of("id", entiteId.toString()),
            Long.class);
    assertEquals(1L, nb, "un signalement déjà actif ne doit pas être dupliqué");
  }

  @Test
  void signaler_signalementResolu_permetUnNouveauSignalement() {
    final UUID entiteId = UUID.randomUUID();
    service.signaler("collision_wikidata_artiste", "artiste", entiteId, "haute", null);
    jdbc.update(
        "UPDATE signalements_matching SET resolu_le = now() WHERE entite_id = CAST(:id AS uuid)",
        Map.of("id", entiteId.toString()));

    service.signaler("collision_wikidata_artiste", "artiste", entiteId, "haute", null);

    final Long nb =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM signalements_matching WHERE entite_id = CAST(:id AS uuid)",
            Map.of("id", entiteId.toString()),
            Long.class);
    assertEquals(2L, nb, "un signalement résolu n'empêche pas un nouveau signalement");
  }

  private long compter(final UUID entiteId) {
    return jdbc.queryForObject(
        "SELECT COUNT(*) FROM signalements_matching WHERE entite_id = CAST(:id AS uuid)",
        Map.of("id", entiteId.toString()),
        Long.class);
  }

  private void resoudreManuellement(final UUID entiteId) {
    jdbc.update(
        "UPDATE signalements_matching SET resolu_le = now(), resolu_manuellement = true"
            + " WHERE entite_id = CAST(:id AS uuid)",
        Map.of("id", entiteId.toString()));
  }

  @Test
  void signaler_dispersionTraiteeALaMainSourcesInverseesEtDistanceDifferente_neRecreeRien() {
    final UUID salle = UUID.randomUUID();
    service.signaler(
        "dispersion_sources_salle",
        "salle",
        salle,
        "haute",
        Map.of("distanceMetres", 405L, "sourceA", "tm:1", "sourceB", "eventim:2"));
    resoudreManuellement(salle);

    service.signaler(
        "dispersion_sources_salle",
        "salle",
        salle,
        "haute",
        Map.of("distanceMetres", 412L, "sourceA", "eventim:2", "sourceB", "tm:1"));

    assertEquals(1L, compter(salle), "la décision de l'administrateur est conservée");
  }

  @Test
  void signaler_dispersionTraiteeALaMainAvecUneAutrePaireDeSources_signaleDeNouveau() {
    final UUID salle = UUID.randomUUID();
    service.signaler(
        "dispersion_sources_salle",
        "salle",
        salle,
        "haute",
        Map.of("distanceMetres", 405L, "sourceA", "tm:1", "sourceB", "eventim:2"));
    resoudreManuellement(salle);

    service.signaler(
        "dispersion_sources_salle",
        "salle",
        salle,
        "haute",
        Map.of("distanceMetres", 900L, "sourceA", "tm:1", "sourceB", "eventim:3"));

    assertEquals(2L, compter(salle), "une autre paire de lieux est une autre anomalie");
  }

  @Test
  void signaler_memeLieuTraiteALaMainMemeAutreSalle_neRecreeRien() {
    final UUID porteuse = UUID.randomUUID();
    final String autre = UUID.randomUUID().toString();
    service.signaler(
        "meme_lieu_salles",
        "salle",
        porteuse,
        "moyenne",
        Map.of("autreSalleId", autre, "distanceMetres", 10L));
    resoudreManuellement(porteuse);

    service.signaler(
        "meme_lieu_salles",
        "salle",
        porteuse,
        "moyenne",
        Map.of("autreSalleId", autre, "distanceMetres", 12L));

    assertEquals(1L, compter(porteuse));
  }

  @Test
  void signaler_signalementClosAutomatiquement_resteSignalableDeNouveau() {
    final UUID salle = UUID.randomUUID();
    final Map<String, Object> details = Map.of("sourceA", "tm:1", "sourceB", "eventim:2");
    service.signaler("dispersion_sources_salle", "salle", salle, "haute", details);
    jdbc.update(
        "UPDATE signalements_matching SET resolu_le = now() WHERE entite_id = CAST(:id AS uuid)",
        Map.of("id", salle.toString()));

    service.signaler("dispersion_sources_salle", "salle", salle, "haute", details);

    assertEquals(2L, compter(salle), "une clôture automatique ne vaut pas décision");
  }
}
