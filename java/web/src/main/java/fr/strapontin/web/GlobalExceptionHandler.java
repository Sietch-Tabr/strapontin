/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web;

import fr.strapontin.web.admin.LogAdminService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Gestionnaire centralisé des exceptions pour tous les contrôleurs REST —
 * retourne systématiquement {@code {"erreur": "..."}}. L'ordre de résolution
 * Spring choisit automatiquement le handler le plus spécifique ; les erreurs
 * 500 sont aussi consignées dans {@code logs_admin} (source {@code web}).
 * @since 2.1.0
 * @author Kwaite
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

  private static final Logger LOG = LoggerFactory.getLogger(GlobalExceptionHandler.class);
  private static final String CLE_ERREUR = "erreur";

  private final LogAdminService logAdminService;

  /**
   * @param logAdminService journalisation des erreurs 500
   */
  public GlobalExceptionHandler(final LogAdminService logAdminService) {
    this.logAdminService = logAdminService;
  }

  /**
   * Retourne le code HTTP porté par l'exception et son message.
   *
   * @param ex l'exception métier
   * @return {@code {"erreur": "message"}} avec le code HTTP de l'exception
   */
  @ExceptionHandler(ErreurMetier.class)
  public ResponseEntity<Map<String, Object>> gererErreurMetier(final ErreurMetier ex) {
    return ResponseEntity.status(ex.getStatus()).body(Map.of(CLE_ERREUR, ex.getMessage()));
  }

  /**
   * Retourne 409 avec le message de l'exception.
   *
   * @param ex l'exception de conflit métier
   * @return {@code {"erreur": "message"}}
   */
  @ExceptionHandler(RessourceDejaExistante.class)
  @ResponseStatus(HttpStatus.CONFLICT)
  public Map<String, Object> gererRessourceDejaExistante(final RessourceDejaExistante ex) {
    return Map.of(CLE_ERREUR, ex.getMessage());
  }

  /**
   * Convertit une erreur de validation Bean Validation en réponse 400.
   *
   * @param ex l'exception de validation
   * @return {@code {"erreur": "premier message de champ"}} ou message générique
   */
  @ExceptionHandler(MethodArgumentNotValidException.class)
  @ResponseStatus(HttpStatus.BAD_REQUEST)
  public Map<String, Object> gererValidation(final MethodArgumentNotValidException ex) {
    final String message =
        ex.getBindingResult().getFieldErrors().stream()
            .map(FieldError::getDefaultMessage)
            .findFirst()
            .orElse("Données invalides.");
    return Map.of(CLE_ERREUR, message);
  }

  /**
   * Retourne 409 en cas de violation de contrainte d'unicité en base.
   *
   * @param ex l'exception de doublon
   * @return {@code {"erreur": "Cette ressource existe déjà."}}
   */
  @ExceptionHandler(DuplicateKeyException.class)
  @ResponseStatus(HttpStatus.CONFLICT)
  public Map<String, Object> gererDoublon(final DuplicateKeyException ex) {
    LOG.debug("Contrainte d'unicité violée : {}", ex.getMessage());
    return Map.of(CLE_ERREUR, "Cette ressource existe déjà.");
  }

  /**
   * Retourne 404 pour les ressources statiques introuvables (favicon, etc.).
   *
   * @param ex l'exception de ressource absente
   * @return {@code {"erreur": "Ressource introuvable."}}
   */
  @ExceptionHandler(NoResourceFoundException.class)
  @ResponseStatus(HttpStatus.NOT_FOUND)
  public Map<String, Object> gererRessourceIntrouvable(final NoResourceFoundException ex) {
    return Map.of(CLE_ERREUR, "Ressource introuvable.");
  }

  /**
   * Retourne 405 pour les méthodes HTTP non supportées sur un endpoint.
   *
   * @param ex l'exception de méthode non supportée
   * @return {@code {"erreur": "Méthode non supportée."}}
   */
  @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
  @ResponseStatus(HttpStatus.METHOD_NOT_ALLOWED)
  public Map<String, Object> gererMethodeNonSupportee(
      final HttpRequestMethodNotSupportedException ex) {
    return Map.of(CLE_ERREUR, "Méthode non supportée.");
  }

  /**
   * Retourne 401 pour les exceptions d'authentification Spring Security.
   *
   * @param ex l'exception d'authentification
   * @return {@code {"erreur": "Authentification requise."}}
   */
  @ExceptionHandler(AuthenticationException.class)
  @ResponseStatus(HttpStatus.UNAUTHORIZED)
  public Map<String, Object> gererErreurAuthentification(final AuthenticationException ex) {
    return Map.of(CLE_ERREUR, "Authentification requise.");
  }

  /**
   * Retourne 500 en cas d'erreur d'accès aux données (hors doublon).
   *
   * @param ex l'exception JDBC/Spring Data
   * @param request la requête HTTP entrante
   * @return {@code {"erreur": "Erreur de base de données."}}
   */
  @ExceptionHandler(DataAccessException.class)
  @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
  public Map<String, Object> gererErreurBd(
      final DataAccessException ex, final HttpServletRequest request) {
    LOG.error("Erreur d'accès aux données", ex);
    logErreurServeur(request, ex);
    return Map.of(CLE_ERREUR, "Erreur de base de données.");
  }

  /**
   * Retourne 500 pour toute exception non traitée par les handlers précédents.
   *
   * @param ex l'exception inattendue
   * @param request la requête HTTP entrante
   * @return {@code {"erreur": "Une erreur inattendue s'est produite."}}
   */
  @ExceptionHandler(Exception.class)
  @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
  public Map<String, Object> gererErreurInattendue(
      final Exception ex, final HttpServletRequest request) {
    LOG.error("Erreur inattendue", ex);
    logErreurServeur(request, ex);
    return Map.of(CLE_ERREUR, "Une erreur inattendue s'est produite.");
  }

  private void logErreurServeur(final HttpServletRequest request, final Exception ex) {
    try {
      final Map<String, Object> meta = new HashMap<>();
      meta.put("chemin", request.getServletPath());
      meta.put("methode", request.getMethod());
      meta.put("exception", ex.getClass().getSimpleName());
      logAdminService.logAdmin(
          "error", "web", "Erreur serveur : " + request.getServletPath(), meta);
    } catch (Exception e) {
      LOG.warn("échec de la journalisation admin d'une erreur serveur", e);
    }
  }
}
