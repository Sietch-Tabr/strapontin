/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.utilisateur;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import fr.strapontin.web.config.ConfigService;
import fr.strapontin.web.securite.UtilisateurDetails;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;

/// Tests unitaires de {@link ParametresRestController}.
///
/// @since 2.7.11
/// @author Kwaite
class ParametresRestControllerTest {

  private ParametresService parametresService;
  private ConfigService configService;
  private ParametresRestController controller;
  private Authentication auth;

  @BeforeEach
  void setUp() {
    parametresService = mock(ParametresService.class);
    configService = mock(ConfigService.class);
    controller = new ParametresRestController(parametresService, configService);

    UtilisateurDetails details =
        new UtilisateurDetails("u1", "alice", "alice@test.fr", "hash", "user", false, false);
    auth = mock(Authentication.class);
    when(auth.getPrincipal()).thenReturn(details);
  }

  @Test
  void emailChange_adresseIdentiqueALActuelle_retourne400() {
    var reponse = controller.emailChange(new RequeteEmailChange("alice@test.fr"), auth);

    assertEquals(400, reponse.getStatusCode().value());
    verify(parametresService, never()).demanderChangementEmail(any(), anyString(), anyString());
  }

  @Test
  void emailChange_adresseDejaUtilisee_retourne400() {
    when(parametresService.emailDejaUtilise("bob@test.fr", "u1")).thenReturn(true);

    var reponse = controller.emailChange(new RequeteEmailChange("bob@test.fr"), auth);

    assertEquals(400, reponse.getStatusCode().value());
    verify(parametresService, never()).demanderChangementEmail(any(), anyString(), anyString());
  }

  @Test
  void emailChange_envoiEmailIndisponible_retourne503() {
    when(parametresService.emailDejaUtilise("bob@test.fr", "u1")).thenReturn(false);
    when(parametresService.emailDisponible()).thenReturn(false);

    var reponse = controller.emailChange(new RequeteEmailChange("bob@test.fr"), auth);

    assertEquals(503, reponse.getStatusCode().value());
  }

  @Test
  void emailChange_publicBaseUrlNonConfiguree_retourne503() {
    when(parametresService.emailDejaUtilise("bob@test.fr", "u1")).thenReturn(false);
    when(parametresService.emailDisponible()).thenReturn(true);
    when(configService.lireConfig("PUBLIC_BASE_URL")).thenReturn(Optional.empty());

    var reponse = controller.emailChange(new RequeteEmailChange("bob@test.fr"), auth);

    assertEquals(503, reponse.getStatusCode().value());
    verify(parametresService, never()).demanderChangementEmail(any(), anyString(), anyString());
  }

  @Test
  void emailChange_toutValide_declencheLeChangementEtRetourne200() {
    when(parametresService.emailDejaUtilise("bob@test.fr", "u1")).thenReturn(false);
    when(parametresService.emailDisponible()).thenReturn(true);
    when(configService.lireConfig("PUBLIC_BASE_URL"))
        .thenReturn(Optional.of("https://strapontin.kwaite.fr"));

    var reponse = controller.emailChange(new RequeteEmailChange(" Bob@Test.fr "), auth);

    assertEquals(200, reponse.getStatusCode().value());
    assertEquals(Boolean.TRUE, reponse.getBody().get("succes"));
    verify(parametresService)
        .demanderChangementEmail(
            any(UtilisateurDetails.class), eq("bob@test.fr"), eq("https://strapontin.kwaite.fr"));
  }

  @Test
  void stats_delegueAuServiceAvecIdUtilisateur() {
    when(parametresService.stats("u1")).thenReturn(Map.of("nbArtistes", 3, "nbSalles", 1));

    var reponse = controller.stats(auth);

    assertEquals(200, reponse.getStatusCode().value());
    assertEquals(3, reponse.getBody().get("nbArtistes"));
  }

  @Test
  void confirmerEmail_succes_redirige302VersCompteAvecSucces() {
    when(parametresService.confirmerEmail("token-abc", "u1"))
        .thenReturn(
            new ParametresService.ResultatConfirmation(
                ParametresService.ResultatConfirmation.Statut.OK, "u1"));

    var reponse = controller.confirmerEmail("token-abc", auth);

    assertEquals(302, reponse.getStatusCode().value());
    assertEquals(
        "/parametres?succes=email-confirme#compte", reponse.getHeaders().getLocation().toString());
  }

  @Test
  void confirmerEmail_lienExpire_redirige302VersErreurLienExpire() {
    when(parametresService.confirmerEmail("token-abc", "u1"))
        .thenReturn(
            new ParametresService.ResultatConfirmation(
                ParametresService.ResultatConfirmation.Statut.LIEN_EXPIRE, "u1"));

    var reponse = controller.confirmerEmail("token-abc", auth);

    assertEquals(302, reponse.getStatusCode().value());
    assertEquals(
        "/parametres?erreur=lien-expire#compte", reponse.getHeaders().getLocation().toString());
  }
}
