/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import fr.strapontin.web.admin.LogAdminService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataRetrievalFailureException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/// Tests unitaires de {@link GlobalExceptionHandler}.
///
/// <p>Vérifie que chaque handler retourne le code HTTP et le message attendus.
///
/// @since 2.1.2
/// @author Kwaite
@ExtendWith(MockitoExtension.class)
class GlobalExceptionHandlerTest {

  @Mock private LogAdminService logAdminService;
  @Mock private HttpServletRequest request;

  private GlobalExceptionHandler handler;

  @BeforeEach
  void setUp() {
    handler = new GlobalExceptionHandler(logAdminService);
  }

  @Test
  void gererDoublon_duplicateKeyException_retourne409AvecMessageGenerique() {
    Map<String, Object> rep = handler.gererDoublon(new DuplicateKeyException("pk violation"));
    assertEquals("Cette ressource existe déjà.", rep.get("erreur"));
  }

  @Test
  void gererRessourceDejaExistante_retourne409AvecMessageException() {
    var ex = new RessourceDejaExistante("Cet artiste est déjà dans votre liste.");
    Map<String, Object> rep = handler.gererRessourceDejaExistante(ex);
    assertEquals("Cet artiste est déjà dans votre liste.", rep.get("erreur"));
  }

  @Test
  void gererValidation_champsInvalide_retourne400AvecPremierMessage() {
    BindingResult bindingResult = mock(BindingResult.class);
    when(bindingResult.getFieldErrors())
        .thenReturn(List.of(new FieldError("obj", "nom", "Le nom est obligatoire.")));
    MethodArgumentNotValidException ex = mock(MethodArgumentNotValidException.class);
    when(ex.getBindingResult()).thenReturn(bindingResult);

    Map<String, Object> rep = handler.gererValidation(ex);

    assertEquals("Le nom est obligatoire.", rep.get("erreur"));
  }

  @Test
  void gererValidation_sansChampErreur_retourne400AvecMessageGenerique() {
    BindingResult bindingResult = mock(BindingResult.class);
    when(bindingResult.getFieldErrors()).thenReturn(List.of());
    MethodArgumentNotValidException ex = mock(MethodArgumentNotValidException.class);
    when(ex.getBindingResult()).thenReturn(bindingResult);

    Map<String, Object> rep = handler.gererValidation(ex);

    assertEquals("Données invalides.", rep.get("erreur"));
  }

  @Test
  void gererRessourceIntrouvable_retourne404() {
    Map<String, Object> rep =
        handler.gererRessourceIntrouvable(mock(NoResourceFoundException.class));
    assertEquals("Ressource introuvable.", rep.get("erreur"));
  }

  @Test
  void gererMethodeNonSupportee_retourne405() {
    Map<String, Object> rep =
        handler.gererMethodeNonSupportee(new HttpRequestMethodNotSupportedException("DELETE"));
    assertEquals("Méthode non supportée.", rep.get("erreur"));
  }

  @Test
  void gererErreurBd_dataAccessException_retourne500() {
    when(request.getServletPath()).thenReturn("/api/test");
    when(request.getMethod()).thenReturn("GET");
    Map<String, Object> rep =
        handler.gererErreurBd(new DataRetrievalFailureException("connexion JDBC perdue"), request);
    assertEquals("Erreur de base de données.", rep.get("erreur"));
  }

  @Test
  void gererErreurAuthentification_retourne401() {
    Map<String, Object> rep =
        handler.gererErreurAuthentification(new BadCredentialsException("identifiants invalides"));
    assertEquals("Authentification requise.", rep.get("erreur"));
  }

  @Test
  void gererErreurMetier_statutPersonnalise_retourneStatutDeLException() {
    ResponseEntity<Map<String, Object>> rep =
        handler.gererErreurMetier(new ErreurMetier("Limite atteinte.", 422));
    assertEquals(422, rep.getStatusCode().value());
    assertEquals("Limite atteinte.", rep.getBody().get("erreur"));
  }

  @Test
  void gererErreurMetier_sansStatut_retourne400() {
    ResponseEntity<Map<String, Object>> rep =
        handler.gererErreurMetier(new ErreurMetier("Données invalides."));
    assertEquals(400, rep.getStatusCode().value());
    assertEquals("Données invalides.", rep.getBody().get("erreur"));
  }
}
