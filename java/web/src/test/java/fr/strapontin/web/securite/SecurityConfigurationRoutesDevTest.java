/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.securite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;

/**
 * Autorisations de {@link SecurityConfiguration} contrôlées sur la vraie chaîne de sécurité (sans
 * base de données ; la protection CSRF de Spring reste active, les POST portent un jeton) : une route refusée l'est avant d'atteindre l'application (401 ou 403), une
 * route autorisée la traverse (ici 404 : aucun contrôleur dans ce contexte). Les routes de mise à
 * jour du dev ne sont autorisées que sur le profil {@code dev} : ailleurs, elles se comportent
 * comme toute route inconnue de l'API, au lieu de tomber dans le repli de l'application (200,
 * page d'accueil) constaté à la livraison de la 3.2.0.
 *
 * @since 3.2.0
 * @author Kwaite
 */
class SecurityConfigurationRoutesDevTest {

  /** Chaîne de la production : profil dev absent. */
  @Configuration
  @EnableWebSecurity
  static class ChaineSansProfilDev {
    @Bean
    SecurityFilterChain chaine(final HttpSecurity http) {
      return construire(http, false);
    }
  }

  /** Chaîne du dev. */
  @Configuration
  @EnableWebSecurity
  static class ChaineProfilDev {
    @Bean
    SecurityFilterChain chaine(final HttpSecurity http) {
      return construire(http, true);
    }
  }

  private AnnotationConfigWebApplicationContext contexte;

  @AfterEach
  void fermer() {
    if (contexte != null) {
      contexte.close();
    }
  }

  private static SecurityFilterChain construire(final HttpSecurity http, final boolean profilDev) {
    try {
      return http.authorizeHttpRequests(
              auth -> SecurityConfiguration.configurerAutorisations(auth, profilDev))
          .build();
    } catch (final Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private MockMvc mvc(final Class<?> configuration) {
    contexte = new AnnotationConfigWebApplicationContext();
    contexte.setServletContext(new MockServletContext());
    contexte.register(configuration);
    contexte.refresh();
    return MockMvcBuilders.webAppContextSetup(contexte).apply(springSecurity()).build();
  }

  private static int statut(final MockMvc mvc, final MockHttpServletRequestBuilder requete)
      throws Exception {
    return mvc.perform(requete).andReturn().getResponse().getStatus();
  }

  private static boolean refusee(final int statut) {
    return statut == 401 || statut == 403;
  }

  @Test
  void sansProfilDev_lesRoutesDeMiseAJourDuDev_sontRefuseesCommeToutesLesRoutesInconnues()
      throws Exception {
    final MockMvc mvc = mvc(ChaineSansProfilDev.class);

    assertTrue(refusee(statut(mvc, get("/api/interne/mise-a-jour-etat"))));
    assertTrue(refusee(statut(mvc, post("/api/interne/mise-a-jour-demande").with(csrf()))));
    assertTrue(refusee(statut(mvc, get("/api/zzz"))));
  }

  @Test
  void sansProfilDev_unJetonNeChangeRien_lAutorisationEstRefusee() throws Exception {
    final MockMvc mvc = mvc(ChaineSansProfilDev.class);

    assertTrue(
        refusee(
            statut(mvc, get("/api/interne/mise-a-jour-etat").header("Authorization", "Bearer x"))));
  }

  @Test
  void profilDev_lesRoutesDeMiseAJourDuDev_traversentLaSecurite() throws Exception {
    final MockMvc mvc = mvc(ChaineProfilDev.class);

    assertEquals(404, statut(mvc, get("/api/interne/mise-a-jour-etat")));
    assertEquals(404, statut(mvc, post("/api/interne/mise-a-jour-demande").with(csrf())));
  }

  @Test
  void profilDev_lesMethodesNonPrevues_restentRefusees() throws Exception {
    final MockMvc mvc = mvc(ChaineProfilDev.class);

    assertTrue(refusee(statut(mvc, post("/api/interne/mise-a-jour-etat").with(csrf()))));
    assertTrue(refusee(statut(mvc, get("/api/interne/mise-a-jour-demande"))));
  }

  @Test
  void quelQueSoitLeProfil_lesEndpointsInternesCommuns_traversentLaSecurite() throws Exception {
    for (final Class<?> configuration :
        new Class<?>[] {ChaineSansProfilDev.class, ChaineProfilDev.class}) {
      final MockMvc mvc = mvc(configuration);

      assertEquals(404, statut(mvc, post("/api/interne/backup-statut").with(csrf())));
      assertEquals(404, statut(mvc, post("/api/interne/integrite-statut").with(csrf())));
      assertEquals(404, statut(mvc, post("/api/interne/mise-a-jour-statut").with(csrf())));
      contexte.close();
    }
  }

  @Test
  void quelQueSoitLeProfil_lAdministrationEtLApiRestentProtegees() throws Exception {
    for (final Class<?> configuration :
        new Class<?>[] {ChaineSansProfilDev.class, ChaineProfilDev.class}) {
      final MockMvc mvc = mvc(configuration);

      assertTrue(refusee(statut(mvc, get("/api/admin/mise-a-jour"))));
      assertTrue(refusee(statut(mvc, get("/api/admin/jobs"))));
      assertTrue(refusee(statut(mvc, get("/api/spectacles"))));
      assertEquals(404, statut(mvc, get("/actuator/health")));
      contexte.close();
    }
  }
}
