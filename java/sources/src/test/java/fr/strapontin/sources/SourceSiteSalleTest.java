/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.sources;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import fr.strapontin.core.BilanFetch;
import fr.strapontin.core.ConcertBrut;
import java.net.ConnectException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;

/**
 * Tests du squelette commun des sources de sites de salles : bilan d'appels, échec propagé avec sa
 * cause, source jamais interrogée à la demande.
 *
 * @since 3.0.6
 * @author Kwaite
 */
class SourceSiteSalleTest {

  private static SourceSiteSalle site(final boolean enEchec) {
    return new SourceSiteSalle(
        new ClientSiteSalle() {
          @Override
          public String lire(final String adresse) {
            if (enEchec) {
              throw new ResourceAccessException("I/O", new ConnectException("refused"));
            }
            return "<html></html>";
          }
        }) {
      @Override
      public String identifiantSite() {
        return "test";
      }

      @Override
      protected List<ConcertBrut> collecter(final LecteurPages lecteur) {
        lecteur.lire("https://exemple.fr/une-page");
        return List.of();
      }
    };
  }

  @Test
  void lireProgrammation_lectureReussie_compteUnAppelSansErreur() {
    final SourceSiteSalle site = site(false);

    site.lireProgrammation();

    assertEquals(new BilanFetch(1, 0), site.preleverBilan());
  }

  @Test
  void lireProgrammation_siteInjoignable_propageEtAttribueLaCause() {
    final SourceSiteSalle site = site(true);

    assertThrows(ResourceAccessException.class, site::lireProgrammation);

    final BilanFetch bilan = site.preleverBilan();
    assertEquals(1, bilan.erreursHttp());
    assertEquals(java.util.Map.of("connexion refusée", 1), bilan.causes());
  }

  @Test
  void nom_prefixeLIdentifiantDuSite() {
    assertEquals("site-test", site(false).nom());
  }

  @Test
  void accepteIdentifiantSalle_neSeFetchePasParLesCyclesDeSallesSuivies() {
    assertFalse(site(false).accepteIdentifiantSalle("site_salle:test"));
  }
}
