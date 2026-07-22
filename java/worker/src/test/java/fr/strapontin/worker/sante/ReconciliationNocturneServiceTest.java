/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker.sante;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import fr.strapontin.sources.TicketmasterSource;
import fr.strapontin.sources.TicketmasterSource.CandidatVenueTM;
import fr.strapontin.sources.TicketmasterSource.StatutIdTM;
import fr.strapontin.worker.LogsAdminWorker;
import fr.strapontin.worker.enrichissement.EnrichissementArtistesService;
import fr.strapontin.worker.enrichissement.EnrichissementSallesService;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/// Tests unitaires pour {@link ReconciliationNocturneService} (#706).
///
/// @since 2.6.0
/// @author Kwaite
class ReconciliationNocturneServiceTest {

  private NamedParameterJdbcTemplate jdbc;
  private TicketmasterSource tm;
  private LogsAdminWorker logsAdminWorker;
  private EnrichissementSallesService enrichissementSalles;
  private EnrichissementArtistesService enrichissementArtistes;
  private CoherenceWikidataSalleService coherenceWikidataSalle;
  private ReconciliationNocturneService service;

  @BeforeEach
  void setUp() {
    jdbc = mock(NamedParameterJdbcTemplate.class);
    tm = mock(TicketmasterSource.class);
    logsAdminWorker = mock(LogsAdminWorker.class);
    enrichissementSalles = mock(EnrichissementSallesService.class);
    enrichissementArtistes = mock(EnrichissementArtistesService.class);
    coherenceWikidataSalle = mock(CoherenceWikidataSalleService.class);
    when(jdbc.queryForList(anyString(), anyMap())).thenReturn(List.of());
    when(enrichissementSalles.enrichir()).thenReturn(0);
    when(enrichissementArtistes.enrichir()).thenReturn(0);
    when(coherenceWikidataSalle.verifier()).thenReturn(0);
    service =
        new ReconciliationNocturneService(
            jdbc,
            tm,
            logsAdminWorker,
            enrichissementSalles,
            enrichissementArtistes,
            coherenceWikidataSalle);
  }

  private static Map<String, Object> ligneSalleSuspecte(UUID salleId, String venueId, String nom) {
    Map<String, Object> ligne = new HashMap<>();
    ligne.put("salle_id", salleId);
    ligne.put("identifiant_externe", venueId);
    ligne.put("nom", nom);
    return ligne;
  }

  @Test
  void executer_aucunSuspect_appelleQuandMemeLesPhasesEnrichissement() {
    ResultatReconciliation resultat = service.executer();

    assertEquals(0, resultat.sallesVerifiees());
    verify(enrichissementSalles).enrichir();
    verify(enrichissementArtistes).enrichir();
    verify(coherenceWikidataSalle).verifier();
  }

  @Test
  void executer_incoherencesWikidataDetectees_propageLeCompteDansLeResultat() {
    when(coherenceWikidataSalle.verifier()).thenReturn(3);

    ResultatReconciliation resultat = service.executer();

    assertEquals(3, resultat.incoherencesWikidata());
  }

  @Test
  void executer_salleVivante_reinitialiseSuspectSansRedecouverte() {
    UUID salleId = UUID.randomUUID();
    when(jdbc.queryForList(contains("ss.source = 'ticketmaster'"), anyMap()))
        .thenReturn(List.of(ligneSalleSuspecte(salleId, "rZ123", "Zenith")));
    when(tm.verifierVenue("rZ123")).thenReturn(StatutIdTM.VIVANT);

    ResultatReconciliation resultat = service.executer();

    assertEquals(1, resultat.sallesVerifiees());
    assertEquals(0, resultat.sallesRedecouvertes());
    verify(tm, never()).rechercherVenuesParNom(any());
    verify(jdbc)
        .update(
            org.mockito.ArgumentMatchers.matches(
                "(?s).*UPDATE salle_sources.*suspect_depuis = NULL.*"),
            org.mockito.ArgumentMatchers.<Map<String, Object>>argThat(
                p -> salleId.toString().equals(p.get("id"))));
  }

  @Test
  void executer_salleMorteUnCandidatValide_redecouvreAutomatiquement() {
    UUID salleId = UUID.randomUUID();
    when(jdbc.queryForList(contains("ss.source = 'ticketmaster'"), anyMap()))
        .thenReturn(List.of(ligneSalleSuspecte(salleId, "ancienId", "Zenith Nantes")));
    when(tm.verifierVenue("ancienId")).thenReturn(StatutIdTM.MORT);
    when(tm.rechercherVenuesParNom("Zenith Nantes"))
        .thenReturn(List.of(new CandidatVenueTM("nouveauId", "Zenith Nantes")));
    when(tm.compterEventsVenue("nouveauId")).thenReturn(5);
    when(jdbc.queryForList(contains("FROM salle_sources WHERE"), anyMap())).thenReturn(List.of());

    ResultatReconciliation resultat = service.executer();

    assertEquals(1, resultat.sallesRedecouvertes());
    assertEquals(0, resultat.ambigues());
    verify(jdbc)
        .update(
            contains("UPDATE salle_sources"),
            org.mockito.ArgumentMatchers.<Map<String, Object>>argThat(
                p -> "nouveauId".equals(p.get("nouveauId"))));
  }

  @Test
  void executer_salleMorteAucunCandidatValide_loggeAmbigu() {
    UUID salleId = UUID.randomUUID();
    when(jdbc.queryForList(contains("ss.source = 'ticketmaster'"), anyMap()))
        .thenReturn(List.of(ligneSalleSuspecte(salleId, "ancienId", "Zenith Nantes")));
    when(tm.verifierVenue("ancienId")).thenReturn(StatutIdTM.MORT);
    when(tm.rechercherVenuesParNom("Zenith Nantes")).thenReturn(List.of());

    ResultatReconciliation resultat = service.executer();

    assertEquals(0, resultat.sallesRedecouvertes());
    assertEquals(1, resultat.ambigues());
    verify(logsAdminWorker).inserer(eq("warn"), eq("reconciliation-nocturne"), any(), any());
  }

  @Test
  void executer_salleMorteDeuxCandidatsValides_loggeAmbigu() {
    UUID salleId = UUID.randomUUID();
    when(jdbc.queryForList(contains("ss.source = 'ticketmaster'"), anyMap()))
        .thenReturn(List.of(ligneSalleSuspecte(salleId, "ancienId", "Zenith Nantes")));
    when(tm.verifierVenue("ancienId")).thenReturn(StatutIdTM.MORT);
    when(tm.rechercherVenuesParNom("Zenith Nantes"))
        .thenReturn(
            List.of(
                new CandidatVenueTM("candidat1", "Zenith Nantes"),
                new CandidatVenueTM("candidat2", "Zenith Nantes Metropole")));
    when(tm.compterEventsVenue(any())).thenReturn(2);
    when(jdbc.queryForList(contains("FROM salle_sources WHERE"), anyMap())).thenReturn(List.of());

    ResultatReconciliation resultat = service.executer();

    assertEquals(0, resultat.sallesRedecouvertes());
    assertEquals(1, resultat.ambigues());
  }

  @Test
  void executer_digitickSuspect_loggeUneSeuleAlerteAgregee() {
    Map<String, Object> ligneDigitick = new HashMap<>();
    ligneDigitick.put("identifiant_externe", "lempreinte");
    ligneDigitick.put("nom", "L'Empreinte");
    when(jdbc.queryForList(contains("source = 'digitick'"), anyMap()))
        .thenReturn(List.of(ligneDigitick));

    service.executer();

    verify(logsAdminWorker, org.mockito.Mockito.times(1))
        .inserer(eq("warn"), eq("reconciliation-nocturne"), contains("Digitick"), any());
  }

  @Test
  void executer_artisteMortRedecouvertParNom_metAJourAutomatiquement() {
    UUID artisteId = UUID.randomUUID();
    Map<String, Object> ligneArtiste = new HashMap<>();
    ligneArtiste.put("artiste_id", artisteId);
    ligneArtiste.put("identifiant_externe", "ancienAttractionId");
    ligneArtiste.put("nom", "Jain");
    when(jdbc.queryForList(contains("artiste_sources asrc JOIN artistes"), anyMap()))
        .thenReturn(List.of(ligneArtiste));
    when(tm.verifierAttraction("ancienAttractionId")).thenReturn(StatutIdTM.MORT);
    when(tm.rechercherAttractionId("Jain")).thenReturn("nouvelAttractionId");
    when(jdbc.queryForList(contains("FROM artiste_sources WHERE"), anyMap())).thenReturn(List.of());

    service.executer();

    verify(jdbc)
        .update(
            contains("UPDATE artiste_sources"),
            org.mockito.ArgumentMatchers.<Map<String, Object>>argThat(
                p -> "nouvelAttractionId".equals(p.get("nouveauId"))));
  }

  @Test
  void executer_artisteMortAucunCandidat_poseNecessiteInterventionLe() {
    UUID artisteId = UUID.randomUUID();
    Map<String, Object> ligneArtiste = new HashMap<>();
    ligneArtiste.put("artiste_id", artisteId);
    ligneArtiste.put("identifiant_externe", "ancienAttractionId");
    ligneArtiste.put("nom", "Jain");
    when(jdbc.queryForList(contains("artiste_sources asrc JOIN artistes"), anyMap()))
        .thenReturn(List.of(ligneArtiste));
    when(tm.verifierAttraction("ancienAttractionId")).thenReturn(StatutIdTM.MORT);
    when(tm.rechercherAttractionId("Jain")).thenReturn(null);

    service.executer();

    verify(jdbc)
        .update(
            org.mockito.ArgumentMatchers.matches(
                "(?s).*UPDATE artiste_sources.*necessite_intervention_le = now\\(\\).*"),
            org.mockito.ArgumentMatchers.<Map<String, Object>>argThat(
                p -> artisteId.toString().equals(p.get("artisteId"))));
    verify(logsAdminWorker)
        .inserer(
            eq("warn"), eq("reconciliation-nocturne"), contains("redécouverte artiste"), any());
  }

  @Test
  void executer_artisteVivant_reinitialiseSuspectSansRedecouverte() {
    UUID artisteId = UUID.randomUUID();
    Map<String, Object> ligneArtiste = new HashMap<>();
    ligneArtiste.put("artiste_id", artisteId);
    ligneArtiste.put("identifiant_externe", "attractionIdVivant");
    ligneArtiste.put("nom", "Yelle");
    when(jdbc.queryForList(contains("artiste_sources asrc JOIN artistes"), anyMap()))
        .thenReturn(List.of(ligneArtiste));
    when(tm.verifierAttraction("attractionIdVivant")).thenReturn(StatutIdTM.VIVANT);

    service.executer();

    verify(tm, never()).rechercherAttractionId(any());
    verify(jdbc)
        .update(
            org.mockito.ArgumentMatchers.matches(
                "(?s).*UPDATE artiste_sources.*suspect_depuis = NULL.*"),
            org.mockito.ArgumentMatchers.<Map<String, Object>>argThat(
                p -> artisteId.toString().equals(p.get("id"))));
  }

  private static Map<String, Object> ligneArtisteLieTM(
      UUID artisteId, String attractionId, String nom) {
    Map<String, Object> ligne = new HashMap<>();
    ligne.put("artiste_id", artisteId);
    ligne.put("identifiant_externe", attractionId);
    ligne.put("nom", nom);
    return ligne;
  }

  // #1012 : le nom retourné par TM peut diverger de l'artiste suivi (tribute, homonyme) sans que
  // l'identifiant soit jamais marqué "suspect" (le tribute a des événements à venir).
  @Test
  void executer_nomTMDivergeDeLArtisteSuivi_poseNecessiteInterventionLeEtJournalise() {
    UUID artisteId = UUID.randomUUID();
    when(jdbc.queryForList(contains("FROM artistes a JOIN artiste_sources asrc"), anyMap()))
        .thenReturn(List.of(ligneArtisteLieTM(artisteId, "K8vZ9174y30", "U2")));
    when(tm.recupererNomAttraction("K8vZ9174y30")).thenReturn("U2 Experience");

    ResultatReconciliation resultat = service.executer();

    assertEquals(1, resultat.nomsIncoherentsTM());
    verify(jdbc)
        .update(
            org.mockito.ArgumentMatchers.matches(
                "(?s).*UPDATE artiste_sources.*necessite_intervention_le = now\\(\\).*"),
            org.mockito.ArgumentMatchers.<Map<String, Object>>argThat(
                p -> artisteId.toString().equals(p.get("artisteId"))));
    verify(logsAdminWorker)
        .inserer(eq("warn"), eq("reconciliation-nocturne"), contains("nom TM incohérent"), any());
  }

  @Test
  void executer_nomTMCoherentAvecLArtisteSuivi_neJournalieRien() {
    UUID artisteId = UUID.randomUUID();
    when(jdbc.queryForList(contains("FROM artistes a JOIN artiste_sources asrc"), anyMap()))
        .thenReturn(List.of(ligneArtisteLieTM(artisteId, "attractionOk", "Angele")));
    when(tm.recupererNomAttraction("attractionOk")).thenReturn("Angèle");

    ResultatReconciliation resultat = service.executer();

    assertEquals(0, resultat.nomsIncoherentsTM());
    verify(logsAdminWorker, never())
        .inserer(eq("warn"), eq("reconciliation-nocturne"), contains("nom TM incohérent"), any());
  }

  @Test
  void executer_nomAttractionIntrouvable_ignoreSansErreur() {
    UUID artisteId = UUID.randomUUID();
    when(jdbc.queryForList(contains("FROM artistes a JOIN artiste_sources asrc"), anyMap()))
        .thenReturn(List.of(ligneArtisteLieTM(artisteId, "idMort", "Furies")));
    when(tm.recupererNomAttraction("idMort")).thenReturn(null);

    ResultatReconciliation resultat = service.executer();

    assertEquals(0, resultat.nomsIncoherentsTM());
  }
}
