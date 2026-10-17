/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.salle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import fr.strapontin.sources.CadenceurAppels;
import fr.strapontin.web.BudgetRecherchesExternes;
import fr.strapontin.web.admin.LogAdminService;
import fr.strapontin.web.config.ConfigService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

/**
 * Tests unitaires pour {@link SalleRechercheNominatimService}.
 *
 * @since 2.9.1
 * @author Kwaite
 */
class SalleRechercheNominatimServiceTest {

  private SalleRechercheNominatimService service;

  @BeforeEach
  void setUp() {
    service =
        new SalleRechercheNominatimService(
            mock(ConfigService.class),
            mock(RestClient.class),
            new ObjectMapper(),
            new BudgetRecherchesExternes(mock(LogAdminService.class)),
            mock(CadenceurAppels.class));
  }

  // une salle taguée leisure/tourism (pas amenity) mais dont extratags.amenity confirme
  // une salle de concert ne doit plus être rejetée (cas réel : Plenitude Arena, Nanterre,
  // class=leisure/type=stadium avec extratags.amenity=music_venue).
  @Test
  void parseNominatimResultats_venueAvecExtratagsAmenity_estAcceptee() {
    final String json =
        """
        [{
          "class": "leisure", "type": "stadium",
          "display_name": "Plenitude Arena, 99, Boulevard Aimé Césaire, Nanterre, France",
          "osm_type": "relation", "osm_id": 14479234,
          "address": {"town": "Nanterre"},
          "extratags": {"amenity": "music_venue", "wikidata": "Q2860806"}
        }]
        """;

    final List<ResultatSalle> resultats = service.parseNominatimResultats(json);

    assertEquals(1, resultats.size());
    assertEquals("Plenitude Arena", resultats.get(0).salle());
    assertEquals("Q2860806", resultats.get(0).wikidataId());
  }

  // class=amenity ne suffit plus à lui seul — un type amenity non pertinent (ex. borne de
  // recharge électrique) doit être rejeté même si class=amenity (cas réel : recherche "plenitude"
  // renvoyant une borne de recharge à Bourgoin-Jallieu avant ce correctif).
  @Test
  void parseNominatimResultats_amenityNonPertinent_estRejetee() {
    final String json =
        """
        [{
          "class": "amenity", "type": "charging_station",
          "display_name": "Plenitude, Avenue Aristide Briand, Bourgoin-Jallieu, France",
          "osm_type": "node", "osm_id": 12853857393,
          "address": {"town": "Bourgoin-Jallieu"},
          "extratags": {"operator": "Plenitude On The Road"}
        }]
        """;

    final List<ResultatSalle> resultats = service.parseNominatimResultats(json);

    assertEquals(0, resultats.size());
  }

  // garde-fou de non-régression pour l'E2E salles_rechercheNominatim_afficheBadgeOsm —
  // un type déjà présent dans TYPES_SALLE_NOMINATIM (arts_centre) reste accepté.
  @Test
  void parseNominatimResultats_typeDejaDansAllowlist_estAcceptee() {
    final String json =
        """
        [{
          "class": "amenity", "type": "arts_centre",
          "display_name": "Rockstore, Montpellier, France",
          "osm_type": "way", "osm_id": 12345,
          "address": {"city": "Montpellier"},
          "extratags": {}
        }]
        """;

    final List<ResultatSalle> resultats = service.parseNominatimResultats(json);

    assertEquals(1, resultats.size());
    assertEquals("Rockstore", resultats.get(0).salle());
  }

  @Test
  void rechercher_plafondGlobalAtteint_neContacteNiLeCadenceurNiNominatim() {
    final BudgetRecherchesExternes budget = mock(BudgetRecherchesExternes.class);
    when(budget.consommer(BudgetRecherchesExternes.Service.NOMINATIM)).thenReturn(false);
    final CadenceurAppels cadenceur = mock(CadenceurAppels.class);
    final RestClient restClient = mock(RestClient.class);
    final SalleRechercheNominatimService limite =
        new SalleRechercheNominatimService(
            mock(ConfigService.class), restClient, new ObjectMapper(), budget, cadenceur);

    assertTrue(limite.rechercher("olympia").isEmpty());

    verifyNoInteractions(cadenceur, restClient);
  }

  @Test
  void rechercher_plafondRespecte_attendLeCreneauPartageAvantLAppel() {
    final CadenceurAppels cadenceur = mock(CadenceurAppels.class);
    final SalleRechercheNominatimService avecCadence =
        new SalleRechercheNominatimService(
            mock(ConfigService.class),
            mock(RestClient.class),
            new ObjectMapper(),
            new BudgetRecherchesExternes(mock(LogAdminService.class)),
            cadenceur);

    avecCadence.rechercher("olympia");

    verify(cadenceur).attendre();
  }
}
