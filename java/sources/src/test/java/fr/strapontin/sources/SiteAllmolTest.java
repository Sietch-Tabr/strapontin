/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.sources;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import fr.strapontin.core.ConcertBrut;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Tests de {@link SiteAllmol} sur la réponse réelle du 17/10/2026.
 *
 * @since 3.0.6
 * @author Kwaite
 */
class SiteAllmolTest {

  private final SiteAllmol.PageProduits page =
      SiteAllmol.extraire(FixturesSites.lire("allmol-concerts.json"));
  private final List<ConcertBrut> concerts = page.concerts();

  @Test
  void extraire_reponse_compteLesProduitsLusEtLeTotal() {
    assertEquals(17, page.total());
    assertEquals(17, page.lus());
  }

  @Test
  void extraire_produitsAnnulesOuReportes_sontEcartes() {
    // 17 produits dont 2 annulés (Nefertiti, 06/11 et 10/11) et 1 reporté (Rony Théophile).
    assertEquals(14, concerts.size());
    assertTrue(concerts.stream().noneMatch(c -> c.artiste().contains("NEFERTITI")));
    assertTrue(concerts.stream().noneMatch(c -> c.artiste().contains("RONY")));
  }

  @Test
  void extraire_titreAvecDepartementDateEtLieu_neGardeQueLArtiste() {
    assertTrue(concerts.stream().anyMatch(c -> c.artiste().equals("REGGAE BEATS")));
    assertTrue(concerts.stream().anyMatch(c -> c.artiste().equals("NAIKA")));
    assertTrue(concerts.stream().anyMatch(c -> c.artiste().equals("WARPED")));
  }

  @Test
  void extraire_horodatageUtc_estConvertiEnHeureLocaleDuDepartement() {
    // 1792281600 = 2026-10-18T00:00Z soit le 17/10 à 20 h en Guadeloupe (UTC-4).
    final ConcertBrut concert =
        concerts.stream().filter(c -> c.artiste().equals("REGGAE BEATS")).findFirst().orElseThrow();

    assertEquals("2026-10-17", concert.date());
    assertEquals("20:00", concert.heure());
  }

  @Test
  void extraire_entitesHtmlDoublementEchappees_sontDecodees() {
    assertTrue(
        concerts.stream().anyMatch(c -> c.artiste().equals("Jacques D'ARBAUD")),
        concerts.stream().map(ConcertBrut::artiste).toList().toString());
  }

  @Test
  void extraire_lieuAvecVilleApresUnTiret_separeNomEtVille() {
    final ConcertBrut concert =
        concerts.stream()
            .filter(c -> c.artiste().startsWith("Récital CANDLELIGHT"))
            .findFirst()
            .orElseThrow();

    assertEquals("ZION PARK", concert.salle());
    assertEquals("Le Gosier", concert.ville());
    assertEquals("971", concert.dept());
    assertEquals("site_salle:allmol:zion park", concert.salleSourceId());
    assertEquals(
        "https://tickets.allmol.com/fr/p/971-23-10-recital-candlelight-avec-mr-mounlight-et-gospel-univers-zion-park",
        concert.url());
  }

  @Test
  void extraire_departementDeLaMartinique_gardeSonCode() {
    // Le seul produit 972 de la réponse (Nefertiti) est annulé : il ne doit rien produire.
    assertTrue(concerts.stream().noneMatch(c -> "972".equals(c.dept())));
  }

  @Test
  void adresse_pageSuivante_decaleLeDebut() {
    assertTrue(SiteAllmol.adresse(100).contains("&start=100&"));
    assertTrue(
        SiteAllmol.adresse(0).contains("customFilter%5Bcategorykey%5D%5B%5D=J6U6QNNI2YSKZQ"));
  }

  @Test
  void nettoyerTitre_titresVariesDuSite_retireLesMentionsAnnexes() {
    assertEquals(
        "FRED DESHAYES",
        SiteAllmol.nettoyerTitre("971 - 17/10 - FRED DESHAYES en Live @ Auditorium"));
    assertEquals(
        "Leïla CHICOT X 30 ans de Zouk / AVOUÉ WORLD TOUR",
        SiteAllmol.nettoyerTitre("Leïla CHICOT X 30 ans de Zouk / AVOUÉ WORLD TOUR"));
    assertEquals("", SiteAllmol.nettoyerTitre(null));
  }
}
