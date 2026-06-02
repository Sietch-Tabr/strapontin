/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.artiste;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import fr.strapontin.web.RessourceDejaExistante;
import fr.strapontin.web.securite.UtilisateurDetails;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;

/// Tests unitaires de {@link ArtisteController}.
///
/// <p>Vérifie la délégation au service et les codes HTTP retournés.
///
/// @since 2.1.2
/// @author Kwaite
@ExtendWith(MockitoExtension.class)
class ArtisteControllerTest {

  @Mock private ArtisteService artisteService;

  private ArtisteController controller;
  private Authentication auth;

  @BeforeEach
  void setUp() {
    controller = new ArtisteController(artisteService);
    UtilisateurDetails ud =
        new UtilisateurDetails("user-1", "kwaite", "Kwaite", "k@test.fr", "", "user", false, false);
    auth = mock(Authentication.class);
    when(auth.getPrincipal()).thenReturn(ud);
  }

  @Test
  void listerArtistes_utilisateurAuthentifie_delegueAuService() {
    ReponseArtistes attendu = new ReponseArtistes(List.of(), List.of(), false);
    when(artisteService.lister("user-1", "")).thenReturn(attendu);

    ResponseEntity<ReponseArtistes> rep = controller.listerArtistes("", auth);

    assertEquals(HttpStatus.OK, rep.getStatusCode());
    assertSame(attendu, rep.getBody());
  }

  @Test
  void ajouterArtiste_nomValide_retourne201AvecSuccesTrue() {
    ResponseEntity<Map<String, Object>> rep =
        controller.ajouterArtiste(new RequeteAjouter("Radiohead"), auth);

    assertEquals(HttpStatus.CREATED, rep.getStatusCode());
    assertEquals(true, rep.getBody().get("succes"));
    verify(artisteService).ajouter("user-1", "Radiohead");
  }

  @Test
  void ajouterArtiste_artisteDejaPresent_propageLexception() {
    doThrow(new RessourceDejaExistante("Déjà suivi."))
        .when(artisteService)
        .ajouter(eq("user-1"), anyString());

    assertThrows(
        RessourceDejaExistante.class,
        () -> controller.ajouterArtiste(new RequeteAjouter("Daft Punk"), auth));
  }

  @Test
  void supprimerArtiste_delegueAuService_retourne204() {
    ResponseEntity<Void> rep = controller.supprimerArtiste("daft punk", auth);

    assertEquals(HttpStatus.NO_CONTENT, rep.getStatusCode());
    verify(artisteService).supprimer("user-1", "daft punk");
  }
}
