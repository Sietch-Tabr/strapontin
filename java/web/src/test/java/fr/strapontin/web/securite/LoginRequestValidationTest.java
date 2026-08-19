/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.securite;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.util.Set;
import org.junit.jupiter.api.Test;

/// Tests unitaires de validation Bean Validation de {@link LoginRequest}.
///
/// @since 2.7.11
/// @author Kwaite
class LoginRequestValidationTest {

  private static final Validator VALIDATOR =
      Validation.buildDefaultValidatorFactory().getValidator();

  @Test
  void identifiantEtMotDePasse_presents_estValide() {
    Set<ConstraintViolation<LoginRequest>> violations =
        VALIDATOR.validate(new LoginRequest("alice", "motdepasse12345"));
    assertTrue(violations.isEmpty());
  }

  @Test
  void identifiant_null_estRejete() {
    Set<ConstraintViolation<LoginRequest>> violations =
        VALIDATOR.validate(new LoginRequest(null, "motdepasse12345"));
    assertFalse(violations.isEmpty());
  }

  @Test
  void motDePasse_vide_estRejete() {
    Set<ConstraintViolation<LoginRequest>> violations =
        VALIDATOR.validate(new LoginRequest("alice", " "));
    assertFalse(violations.isEmpty());
  }
}
