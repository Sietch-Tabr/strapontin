/*
 * Strapontin — concert finder
 * Copyright (C) 2025 Kwaite
 */
package fr.strapontin.web;

import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/// Gestionnaire centralisé des erreurs de validation Bean Validation.
///
/// <p>Intercepte {@link MethodArgumentNotValidException} et retourne {@code {"erreur": "..."}},
/// cohérent avec le format d'erreur de tous les contrôleurs REST.
///
/// @since 2.0.0
/// @author Kwaite
@RestControllerAdvice
public class ValidationExceptionHandler {

  /// Convertit une erreur de validation en réponse 400 JSON.
  ///
  /// @param ex l'exception de validation
  /// @return {@code {"erreur": "premier message de validation"}}
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
}
