/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.securite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import fr.strapontin.web.config.ConfigService;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Tests unitaires de {@link ProtectionCsrfFilter}.
 *
 * @since 3.0.2
 * @author Kwaite
 */
class ProtectionCsrfFilterTest {

  private ConfigService configService;
  private ProtectionCsrfFilter filtre;

  @BeforeEach
  void setUp() {
    configService = mock(ConfigService.class);
    when(configService.lireConfig("PUBLIC_BASE_URL"))
        .thenReturn(Optional.of("https://strapontin.kwaite.fr"));
    filtre = new ProtectionCsrfFilter(configService);
  }

  @Test
  void requeteDuFrontMemeOrigine_laissePasser() throws Exception {
    final MockHttpServletRequest requete = requeteDuFront("POST", "/api/preferences/importer");
    requete.addHeader("Origin", "http://localhost");

    assertNotNull(executer(requete).getRequest());
  }

  @Test
  void requeteSousDomaineVoisin_sameSite_refuse403() throws Exception {
    final MockHttpServletRequest requete =
        new MockHttpServletRequest("POST", "/api/admin/vapid/generer");
    requete.addHeader(
        ProtectionCsrfFilter.ENTETE_APPLICATION, ProtectionCsrfFilter.VALEUR_ENTETE_APPLICATION);
    requete.addHeader("Sec-Fetch-Site", "same-site");

    assertNull(executer(requete).getRequest());
  }

  @Test
  void requeteAutreOrigine_refuse403() throws Exception {
    final MockHttpServletRequest requete = requeteDuFront("DELETE", "/api/salles/1");
    requete.addHeader("Origin", "https://domotique.kwaite.fr");

    assertNull(executer(requete).getRequest());
  }

  @Test
  void requeteOriginePubliqueDerriereLeProxy_laissePasser() throws Exception {
    final MockHttpServletRequest requete = requeteDuFront("POST", "/api/push");
    requete.addHeader("Origin", "https://strapontin.kwaite.fr");

    assertNotNull(executer(requete).getRequest());
  }

  @Test
  void requeteSansEnteteApplicatif_formulaireMultipart_refuse403() throws Exception {
    final MockHttpServletRequest requete = new MockHttpServletRequest("POST", "/api/auth/logout");

    final MockHttpServletResponse reponse = new MockHttpServletResponse();
    final MockFilterChain chaine = new MockFilterChain();
    filtre.doFilter(requete, reponse, chaine);

    assertEquals(403, reponse.getStatus());
    assertNull(chaine.getRequest());
  }

  @Test
  void lectureGet_neFiltrePas() throws Exception {
    assertNotNull(executer(new MockHttpServletRequest("GET", "/api/salles")).getRequest());
  }

  @Test
  void webhookServeurAServeur_neFiltrePas() throws Exception {
    assertNotNull(
        executer(new MockHttpServletRequest("POST", "/api/webhooks/resend")).getRequest());
  }

  private static MockHttpServletRequest requeteDuFront(final String methode, final String uri) {
    final MockHttpServletRequest requete = new MockHttpServletRequest(methode, uri);
    requete.addHeader(
        ProtectionCsrfFilter.ENTETE_APPLICATION, ProtectionCsrfFilter.VALEUR_ENTETE_APPLICATION);
    requete.addHeader("Sec-Fetch-Site", "same-origin");
    return requete;
  }

  private MockFilterChain executer(final MockHttpServletRequest requete) throws Exception {
    final MockFilterChain chaine = new MockFilterChain();
    filtre.doFilter(requete, new MockHttpServletResponse(), chaine);
    return chaine;
  }
}
