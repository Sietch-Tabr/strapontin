/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker.interne;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import fr.strapontin.core.JetonInterneWorker;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Tests unitaires de {@link FiltreJetonInterne}.
 *
 * @since 3.0.2
 * @author Kwaite
 */
class FiltreJetonInterneTest {

  private final FiltreJetonInterne filtre = new FiltreJetonInterne("secret-test");

  @Test
  void doFilter_sansJeton_refuse403SansAtteindreLeControleur() throws Exception {
    final MockHttpServletResponse reponse = new MockHttpServletResponse();
    final MockFilterChain chaine = new MockFilterChain();

    filtre.doFilter(new MockHttpServletRequest("POST", "/trigger/tout"), reponse, chaine);

    assertEquals(403, reponse.getStatus());
    assertNull(chaine.getRequest());
  }

  @Test
  void doFilter_jetonValide_laissePasser() throws Exception {
    final MockHttpServletRequest requete = new MockHttpServletRequest("POST", "/trigger/tout");
    requete.addHeader(JetonInterneWorker.ENTETE, JetonInterneWorker.calculer("secret-test"));
    final MockHttpServletResponse reponse = new MockHttpServletResponse();
    final MockFilterChain chaine = new MockFilterChain();

    filtre.doFilter(requete, reponse, chaine);

    assertEquals(200, reponse.getStatus());
    assertNotNull(chaine.getRequest());
  }
}
