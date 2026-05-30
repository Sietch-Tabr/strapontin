/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web;

import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/// Gestionnaire centralisé des exceptions pour tous les contrôleurs REST.
///
/// <p>Retourne systématiquement `{"erreur": "..."}`, cohérent avec le format d'erreur de l'API.
/// L'ordre de résolution Spring choisit automatiquement le handler le plus spécifique
/// ({@link DuplicateKeyException} avant {@link DataAccessException}, etc.).
///
/// @since 2.1.0
/// @author Kwaite
@RestControllerAdvice
public class GlobalExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

  /// Retourne le code HTTP porté par l'exception et son message.
  ///
  /// @param ex l'exception métier
  /// @return `{"erreur": "message"}` avec le code HTTP de l'exception
  @ExceptionHandler(ErreurMetier.class)
  public ResponseEntity<Map<String, Object>> gererErreurMetier(ErreurMetier ex) {
    return ResponseEntity.status(ex.getStatus()).body(Map.of("erreur", ex.getMessage()));
  }

  /// Retourne 409 avec le message de l'exception.
  ///
  /// @param ex l'exception de conflit métier
  /// @return `{"erreur": "message"}`
  @ExceptionHandler(RessourceDejaExistante.class)
  @ResponseStatus(HttpStatus.CONFLICT)
  public Map<String, Object> gererRessourceDejaExistante(RessourceDejaExistante ex) {
    return Map.of("erreur", ex.getMessage());
  }

  /// Convertit une erreur de validation Bean Validation en réponse 400.
  ///
  /// @param ex l'exception de validation
  /// @return `{"erreur": "premier message de champ"}` ou message générique
  @ExceptionHandler(MethodArgumentNotValidException.class)
  @ResponseStatus(HttpStatus.BAD_REQUEST)
  public Map<String, Object> gererValidation(MethodArgumentNotValidException ex) {
    String message =
        ex.getBindingResult().getFieldErrors().stream()
            .map(fe -> fe.getDefaultMessage())
            .findFirst()
            .orElse("Données invalides.");
    return Map.of("erreur", message);
  }

  /// Retourne 409 en cas de violation de contrainte d'unicité en base.
  ///
  /// @param ex l'exception de doublon
  /// @return `{"erreur": "Cette ressource existe déjà."}`
  @ExceptionHandler(DuplicateKeyException.class)
  @ResponseStatus(HttpStatus.CONFLICT)
  public Map<String, Object> gererDoublon(DuplicateKeyException ex) {
    log.debug("Contrainte d'unicité violée : {}", ex.getMessage());
    return Map.of("erreur", "Cette ressource existe déjà.");
  }

  /// Retourne 500 en cas d'erreur d'accès aux données (hors doublon).
  ///
  /// @param ex l'exception JDBC/Spring Data
  /// @return `{"erreur": "Erreur de base de données."}`
  @ExceptionHandler(DataAccessException.class)
  @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
  public Map<String, Object> gererErreurBd(DataAccessException ex) {
    log.error("Erreur d'accès aux données", ex);
    return Map.of("erreur", "Erreur de base de données.");
  }

  /// Retourne 500 pour toute exception non traitée par les handlers précédents.
  ///
  /// @param ex l'exception inattendue
  /// @return `{"erreur": "Une erreur inattendue s'est produite."}`
  @ExceptionHandler(Exception.class)
  @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
  public Map<String, Object> gererErreurInattendue(Exception ex) {
    log.error("Erreur inattendue", ex);
    return Map.of("erreur", "Une erreur inattendue s'est produite.");
  }
}
