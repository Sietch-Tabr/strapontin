/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker.sante;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import fr.strapontin.sources.TicketmasterSource;
import fr.strapontin.worker.AbstractWorkerIntegrationTest;
import java.sql.Types;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Dispersion géographique des lieux sources ({@link ControleDispersionGeo}) sur base réelle.
 *
 * @since 3.0.2
 * @author Kwaite
 */
class ControleDispersionGeoIntegrationTest extends AbstractWorkerIntegrationTest {

  @MockitoBean TicketmasterSource ticketmasterSource;

  @Autowired private NamedParameterJdbcTemplate jdbc;
  @Autowired private SignalementMatchingService signalements;

  private ControleDispersionGeo controle;

  @BeforeEach
  void setUp() {
    controle = new ControleDispersionGeo(jdbc, signalements);
  }

  private UUID creerSalle(final String nom) {
    final UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO salles (id, salle_cle, nom, nom_norm) VALUES (CAST(:id AS uuid), :id, :nom,"
            + " :nom)",
        Map.of("id", id.toString(), "nom", nom));
    return id;
  }

  // Le code postal est celui qui qualifie l'identifiant (internal:<nom>@<code postal>).
  private void lieu(
      final UUID salle, final String identifiant, final double lat, final double lon) {
    final int arobase = identifiant.lastIndexOf('@');
    jdbc.update(
        "INSERT INTO salle_sources (salle_id, source, identifiant_externe, latitude, longitude,"
            + " code_postal) VALUES (CAST(:id AS uuid), 'eventim', :identifiant, :lat, :lon, :cp)",
        new MapSqlParameterSource()
            .addValue("id", salle.toString())
            .addValue("identifiant", identifiant)
            .addValue("lat", lat)
            .addValue("lon", lon)
            .addValue(
                "cp", arobase > 0 ? identifiant.substring(arobase + 1) : null, Types.VARCHAR));
  }

  private List<String> typesSignales() {
    return jdbc.queryForList(
        "SELECT type FROM signalements_matching ORDER BY type", Map.of(), String.class);
  }

  // La Comédie Bastille (11e) et la Comédie de Paris (9e), ~3 km, sous une même salle.
  @Test
  void verifier_salleAuxLieuxSourcesEloignes_signaleUneDispersion() {
    final UUID salle = creerSalle("la comedie bastille paris");
    lieu(salle, "internal:la comedie bastille paris@75011", 48.8559, 2.3713);
    lieu(salle, "internal:comedie de paris paris 09@75009", 48.8820, 2.3361);

    assertEquals(1, controle.verifier());
    assertEquals(List.of("dispersion_sources_salle"), typesSignales());
  }

  // Élysée Montmartre et Théâtre Montmartre-Galabru, 818 m, même arrondissement : le mot
  // « montmartre » les avait réunis sous une seule salle.
  @Test
  void verifier_lieuxVoisinsAuxNomsDistincts_signaleUneDispersion() {
    final UUID salle = creerSalle("theatre montmartre galabru paris 18");
    lieu(salle, "internal:elysee montmartre paris 18@75018", 48.88310, 2.34368);
    lieu(salle, "internal:theatre montmartre galabru paris 18@75018", 48.88732, 2.33453);

    assertEquals(1, controle.verifier());
    assertEquals(List.of("dispersion_sources_salle"), typesSignales());
  }

  // Théâtre Galli : une billetterie complète le nom de la ville, positions à 374 m.
  @Test
  void verifier_memeLieuNomCompleteDeSaVille_neSignalePas() {
    final UUID salle = creerSalle("theatre galli");
    jdbc.update(
        "UPDATE salles SET ville = 'Sanary-sur-Mer' WHERE id = CAST(:id AS uuid)",
        Map.of("id", salle.toString()));
    lieu(salle, "internal:theatre galli@83110", 43.11880, 5.80140);
    lieu(salle, "internal:theatre galli sanary sur mer@83110", 43.12190, 5.80310);

    assertEquals(0, controle.verifier());
  }

  // Théâtre de Poche-Montparnasse rattaché au Théâtre Montparnasse : « poche » n'est ni la
  // ville ni un mot du nom de la salle, deux lieux à 477 m.
  @Test
  void verifier_nomCompleteDUnMotDistinctif_signaleUneDispersion() {
    final UUID salle = creerSalle("theatre montparnasse");
    jdbc.update(
        "UPDATE salles SET ville = 'Paris' WHERE id = CAST(:id AS uuid)",
        Map.of("id", salle.toString()));
    lieu(salle, "internal:theatre montparnasse@75014", 48.839567, 2.323496);
    lieu(salle, "internal:theatre de poche montparnasse paris@75006", 48.843734, 2.325072);

    assertEquals(1, controle.verifier());
  }

  // L'Européen publié sous deux noms (« Leuropeen Paris 17 ») : même lieu, deux salles.
  @Test
  void verifier_deuxSallesAuMemeLieuAuxNomsCompatibles_signaleUnDoublon() {
    final UUID europeen = creerSalle("l europeen");
    final UUID leuropeen = creerSalle("leuropeen paris 17");
    lieu(europeen, "internal:l europeen@75017", 48.88370, 2.32720);
    lieu(leuropeen, "internal:leuropeen paris 17@75017", 48.88390, 2.32740);

    assertEquals(1, controle.verifier());
    assertEquals(List.of("meme_lieu_salles"), typesSignales());
  }

  // La Cigale et La Boule Noire : même bâtiment, deux salles distinctes.
  @Test
  void verifier_deuxSallesVoisinesAuxNomsDistincts_neSignalePas() {
    final UUID cigale = creerSalle("la cigale");
    final UUID bouleNoire = creerSalle("la boule noire");
    lieu(cigale, "internal:la cigale@75018", 48.88220, 2.34040);
    lieu(bouleNoire, "internal:la boule noire paris 18@75018", 48.88225, 2.34045);

    assertEquals(0, controle.verifier());
  }

  // L'Européen publié « Leuropeen Paris 17 » par une seconde billetterie : même lieu, à fusionner.
  @Test
  void pairesMemeLieuFortes_memeLieuSousDeuxNoms_retenue() {
    final UUID europeen = creerSalle("l europeen");
    final UUID doublon = creerSalle("leuropeen paris 17");
    lieu(europeen, "internal:l europeen@75017", 48.88310, 2.32700);
    lieu(doublon, "internal:leuropeen paris 17@75017", 48.88312, 2.32702);

    final List<UUID[]> paires = controle.pairesMemeLieuFortes();

    assertEquals(1, paires.size());
    assertEquals(
        java.util.Set.of(europeen, doublon), java.util.Set.of(paires.get(0)[0], paires.get(0)[1]));
  }

  @Test
  void pairesMemeLieuFortes_sallesVoisinesDistinctes_aucune() {
    final UUID cigale = creerSalle("la cigale");
    final UUID bouleNoire = creerSalle("la boule noire");
    lieu(cigale, "internal:la cigale@75018", 48.88220, 2.34040);
    lieu(bouleNoire, "internal:la boule noire paris 18@75018", 48.88225, 2.34045);

    assertEquals(0, controle.pairesMemeLieuFortes().size());
  }

  @Test
  void verifier_complexeEtSousSalleAuMemeLieu_neSignalePas() {
    final UUID complexe = creerSalle("la seine musicale");
    final UUID sousSalle = creerSalle("la seine musicale grande seine");
    jdbc.update(
        "UPDATE salles SET salle_parent_id = CAST(:p AS uuid) WHERE id = CAST(:id AS uuid)",
        Map.of("p", complexe.toString(), "id", sousSalle.toString()));
    lieu(complexe, "internal:la seine musicale@92100", 48.8296, 2.2404);
    lieu(sousSalle, "internal:grande seine@92100", 48.8296, 2.2404);

    assertEquals(0, controle.verifier());
  }

  // Zénith d'Auvergne : Eventim publie une position décalée de 3 km, même code postal que TM.
  @Test
  void verifier_positionsDecaleesMemeCodePostal_neSignalePasDeDispersion() {
    final UUID zenith = creerSalle("zenith d auvergne");
    lieu(zenith, "internal:zenith dauvergne clermont ferrand@63800", 45.7403, 3.1537);
    lieu(zenith, "internal:zenith d auvergne@63800", 45.7424, 3.1966);

    assertEquals(0, controle.verifier());
  }

  // « Comédie de Paris » géocodée à Montpellier : la position de ses lieux sources la remplace.
  @Test
  void consoliderPositions_salleGeocodeeChezUnHomonyme_prendLaPositionDeSesSources() {
    final UUID salle = creerSalle("comedie de paris paris 09");
    jdbc.update(
        "UPDATE salles SET latitude = 43.6068, longitude = 3.8764, origine_position = 'geocodage'"
            + " WHERE id = CAST(:id AS uuid)",
        Map.of("id", salle.toString()));
    lieu(salle, "internal:comedie de paris paris 09@75009", 48.8820, 2.3361);

    assertEquals(1, controle.consoliderPositions());

    assertEquals(
        48.8820,
        jdbc.queryForObject(
            "SELECT latitude FROM salles WHERE id = CAST(:id AS uuid)",
            Map.of("id", salle.toString()),
            Double.class),
        1e-9);
  }

  private void positionner(
      final UUID salle,
      final String dept,
      final double lat,
      final double lon,
      final String origine) {
    jdbc.update(
        "UPDATE salles SET dept = :dept, latitude = :lat, longitude = :lon,"
            + " origine_position = :origine WHERE id = CAST(:id AS uuid)",
        Map.of("id", salle.toString(), "dept", dept, "lat", lat, "lon", lon, "origine", origine));
  }

  // « Grand Palais » (75) géocodé en Charente : la position d'un homonyme s'efface.
  @Test
  void verifier_positionGeocodeeHorsDepartement_estEffacee() {
    final UUID salle = creerSalle("grand palais");
    positionner(salle, "75", 45.1388794, -0.155064, "geocodage");

    controle.verifier();

    assertNull(
        jdbc.queryForObject(
            "SELECT latitude FROM salles WHERE id = CAST(:id AS uuid)",
            Map.of("id", salle.toString()),
            Double.class));
    assertTrue(typesSignales().isEmpty());
  }

  // Rockhal (Esch-sur-Alzette) rangée dans le 43 : la position est juste, le département non.
  @Test
  void verifier_positionSourcesHorsDepartement_estSignalee() {
    final UUID salle = creerSalle("rockhal");
    positionner(salle, "43", 49.5014, 5.9481, "sources");

    controle.verifier();

    assertEquals(List.of("position_hors_departement"), typesSignales());
  }

  @Test
  void verifier_positionDansSonDepartement_nEstNiEffaceeNiSignalee() {
    final UUID salle = creerSalle("le balajo");
    positionner(salle, "75", 48.8540169, 2.3721601, "geocodage");

    controle.verifier();

    assertEquals(
        48.8540169,
        jdbc.queryForObject(
            "SELECT latitude FROM salles WHERE id = CAST(:id AS uuid)",
            Map.of("id", salle.toString()),
            Double.class),
        1e-9);
    assertTrue(typesSignales().isEmpty());
  }

  @Test
  void consoliderPositions_positionWikidata_estConservee() {
    final UUID salle = creerSalle("sud de france arena");
    jdbc.update(
        "UPDATE salles SET latitude = 43.5722, longitude = 3.9522, origine_position = 'wikidata'"
            + " WHERE id = CAST(:id AS uuid)",
        Map.of("id", salle.toString()));
    lieu(salle, "ticketmaster:centre-montpellier@34000", 43.6082, 3.8794);

    assertEquals(0, controle.consoliderPositions());
  }

  @Test
  void verifier_positionWikidataDUnHomonymeLointain_signaleLIncoherence() {
    final UUID salle = creerSalle("espace des arts");
    jdbc.update(
        "UPDATE salles SET latitude = 46.779, longitude = 4.849, origine_position = 'wikidata',"
            + " wikidata_id = 'Q61860839' WHERE id = CAST(:id AS uuid)",
        Map.of("id", salle.toString()));
    lieu(salle, "ticketmaster:rZ6SnyZ1a6@93320", 48.903, 2.508);

    assertEquals(1, controle.verifier());
    assertEquals(List.of("position_wikidata_incoherente"), typesSignales());
  }

  // Église luthérienne de la Trinité (13e) rattachée à l'église luthérienne Saint-Pierre, 5,5 km.
  @Test
  void verifier_positionWikidataDUnLieuVoisin_signaleLIncoherence() {
    final UUID salle = creerSalle("eglise lutherienne de la trinite paris 13");
    jdbc.update(
        "UPDATE salles SET latitude = 48.8826, longitude = 2.3826, origine_position = 'wikidata',"
            + " wikidata_id = 'Q60384798' WHERE id = CAST(:id AS uuid)",
        Map.of("id", salle.toString()));
    lieu(salle, "internal:eglise lutherienne de la trinite paris 13@75013", 48.8336, 2.3618);

    assertEquals(1, controle.verifier());
    assertEquals(List.of("position_wikidata_incoherente"), typesSignales());
  }
}
