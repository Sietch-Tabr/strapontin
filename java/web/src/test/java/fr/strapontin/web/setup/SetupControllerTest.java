/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.setup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import fr.strapontin.web.RessourceDejaExistante;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * Tests unitaires de {@link SetupController}.
 *
 * <p>Vérifie la délégation au service et les codes HTTP retournés.
 *
 * @since 2.6.6
 * @author Kwaite
 */
@ExtendWith(MockitoExtension.class)
class SetupControllerTest {

  @Mock private SetupService setupService;

  private SetupController controller;

  @BeforeEach
  void setUp() {
    controller = new SetupController(setupService);
  }

  private static RequeteSetup requeteValide() {
    return new RequeteSetup(
        "admin", "admin@exemple.fr", "motdepasse12345", "https://strapontin.fr", null);
  }

  @Test
  void verifierSetup_dejaConfiguree_retourneVrai() {
    when(setupService.estDejaConfiguree()).thenReturn(true);

    ResponseEntity<ReponseSetup> rep = controller.verifierSetup();

    assertEquals(HttpStatus.OK, rep.getStatusCode());
    assertEquals(true, rep.getBody().dejaConfiguree());
  }

  @Test
  void verifierSetup_pasEncoreConfiguree_retourneFaux() {
    when(setupService.estDejaConfiguree()).thenReturn(false);

    ResponseEntity<ReponseSetup> rep = controller.verifierSetup();

    assertEquals(false, rep.getBody().dejaConfiguree());
  }

  @Test
  void initialiser_requeteValide_retourne201EtDelegueAuService() {
    ResponseEntity<Map<String, Object>> rep = controller.initialiser(requeteValide());

    assertEquals(HttpStatus.CREATED, rep.getStatusCode());
    assertEquals(true, rep.getBody().get("succes"));
    verify(setupService).initialiser(requeteValide());
  }

  @Test
  void initialiser_serviceLeveRessourceDejaExistante_propageLexception() {
    doThrow(new RessourceDejaExistante("L'application est déjà configurée."))
        .when(setupService)
        .initialiser(requeteValide());

    assertEquals(
        "L'application est déjà configurée.",
        assertThrows(RessourceDejaExistante.class, () -> controller.initialiser(requeteValide()))
            .getMessage());
  }
}
