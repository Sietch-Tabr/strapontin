/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker.fetch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import fr.strapontin.core.ConcertBrut;
import fr.strapontin.sources.enrichissement.ClientNominatim;
import fr.strapontin.sources.enrichissement.ResolveurNomCanonique;
import fr.strapontin.sources.enrichissement.ResolveurNomCanonique.ResultatResolution;
import fr.strapontin.sources.enrichissement.ResolveurNomCanonique.SourceResolution;
import fr.strapontin.sources.sites.ClientSiteSalle;
import fr.strapontin.sources.sites.SourceSiteSalle;
import fr.strapontin.sources.ticketmaster.TicketmasterSource;
import fr.strapontin.worker.AbstractWorkerIntegrationTest;
import fr.strapontin.worker.persister.PersisterConcerts;
import fr.strapontin.worker.persister.RecalculerMatchesService;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Lecture quotidienne des sites de salles sur base réelle : persistance, absences comptées
 * seulement après une lecture complète.
 *
 * @since 3.0.6
 * @author Kwaite
 */
class FetchSitesSallesExecutorIntegrationTest extends AbstractWorkerIntegrationTest {

  private static final String DATE = LocalDate.now().plusMonths(2).toString();

  @MockitoBean private TicketmasterSource ticketmasterSource;
  @MockitoBean private ClientNominatim clientNominatim;
  @MockitoBean private ResolveurNomCanonique resolveurNomCanonique;

  @Autowired private PersisterConcerts persisterConcerts;
  @Autowired private RecalculerMatchesService recalculerMatchesService;
  @Autowired private FetchRapportage rapportage;
  @Autowired private WorkerFetchCommun commun;
  @Autowired private NamedParameterJdbcTemplate jdbc;

  /** Site de test : programmation et panne réglables. */
  private static final class SiteDeTest extends SourceSiteSalle {

    private List<String> artistes = List.of();
    private boolean enPanne;

    private SiteDeTest() {
      super(new ClientSiteSalle());
    }

    @Override
    public String identifiantSite() {
      return "faux";
    }

    @Override
    protected List<ConcertBrut> collecter(final LecteurPages lecteur) {
      if (enPanne) {
        throw new IllegalStateException("site injoignable");
      }
      return artistes.stream()
          .map(
              artiste ->
                  ConcertBrut.nouveau()
                      .artiste(artiste)
                      .date(DATE)
                      .heure("20:30")
                      .salle("Salle du Faux Site")
                      .ville("Rambouillet")
                      .dept("78")
                      .salleSourceId("site_salle:faux")
                      .url("https://faux.example/" + artiste.replace(' ', '-'))
                      .source(SOURCE)
                      .classification("music")
                      .construire())
          .toList();
    }
  }

  private SiteDeTest site;
  private FetchSitesSallesExecutor executor;

  @BeforeEach
  void preparer() {
    when(resolveurNomCanonique.resoudreNomCanonique(anyString(), anyString()))
        .thenAnswer(
            invocation ->
                new ResultatResolution(invocation.getArgument(1), SourceResolution.INCHANGE));
    when(clientNominatim.geocoder(anyString())).thenReturn(Optional.empty());
    jdbc.update("DELETE FROM user_matches", Map.of());
    jdbc.update("DELETE FROM evenements", Map.of());
    jdbc.update("DELETE FROM salle_sources", Map.of());
    jdbc.update("DELETE FROM salles", Map.of());
    jdbc.update("DELETE FROM logs_admin", Map.of());
    site = new SiteDeTest();
    executor =
        new FetchSitesSallesExecutor(
            List.of(site), persisterConcerts, recalculerMatchesService, rapportage, commun);
  }

  private int absences(final String artisteNorm) {
    return jdbc.queryForObject(
        "SELECT absences_consecutives FROM evenements WHERE artiste_norm = :a",
        Map.of("a", artisteNorm),
        Integer.class);
  }

  @Test
  void executer_siteAvecDeuxConcerts_lesPersisteSousLaSourceDesSites() {
    site.artistes = List.of("Groupe Un", "Groupe Deux");

    executor.executer();

    assertEquals(2L, jdbc.queryForObject("SELECT count(*) FROM evenements", Map.of(), Long.class));
    assertEquals(
        2L,
        jdbc.queryForObject(
            "SELECT count(*) FROM observations WHERE source = 'site_salle'", Map.of(), Long.class));
    assertEquals(
        1L,
        jdbc.queryForObject(
            "SELECT count(*) FROM salle_sources WHERE source = 'site_salle'"
                + " AND identifiant_externe = 'faux'",
            Map.of(),
            Long.class));
  }

  @Test
  void executer_concertQueLeSiteNAnnonceplus_gagneUneAbsence() {
    site.artistes = List.of("Groupe Un", "Groupe Deux");
    executor.executer();

    site.artistes = List.of("Groupe Un");
    executor.executer();

    assertEquals(0, absences("groupe un"));
    assertEquals(1, absences("groupe deux"));
  }

  @Test
  void executer_siteInjoignable_neCompteAucuneAbsenceEtJournaliseLEchec() {
    site.artistes = List.of("Groupe Un", "Groupe Deux");
    executor.executer();

    site.enPanne = true;
    executor.executer();

    assertEquals(0, absences("groupe un"));
    assertEquals(0, absences("groupe deux"));
    assertEquals(
        1L,
        jdbc.queryForObject(
            "SELECT count(*) FROM logs_admin WHERE niveau = 'error' AND message LIKE '%site-faux%'",
            Map.of(), Long.class));
  }

  @Test
  void executer_siteQuiNeRendPlusRienAlorsQueLaBaseEnConnait_neCompteAucuneAbsence() {
    site.artistes = List.of("Groupe Un", "Groupe Deux");
    executor.executer();

    site.artistes = List.of();
    executor.executer();

    assertEquals(0, absences("groupe un"));
    assertEquals(0, absences("groupe deux"));
    assertEquals(
        1L,
        jdbc.queryForObject(
            "SELECT count(*) FROM logs_admin WHERE message LIKE '%mise en page modifiée%'"
                + " OR message LIKE '%site-faux%'",
            Map.of(), Long.class));
  }
}
