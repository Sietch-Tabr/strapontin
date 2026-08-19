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

/// Tests unitaires de validation Bean Validation de {@link TotpChallengeRequest}.
///
/// @since 2.7.11
/// @author Kwaite
class TotpChallengeRequestValidationTest {

  private static final Validator VALIDATOR =
      Validation.buildDefaultValidatorFactory().getValidator();

  @Test
  void code_present_estValide() {
    Set<ConstraintViolation<TotpChallengeRequest>> violations =
        VALIDATOR.validate(new TotpChallengeRequest("123456"));
    assertTrue(violations.isEmpty());
  }

  @Test
  void code_null_estRejete() {
    Set<ConstraintViolation<TotpChallengeRequest>> violations =
        VALIDATOR.validate(new TotpChallengeRequest(null));
    assertFalse(violations.isEmpty());
  }

  @Test
  void code_vide_estRejete() {
    Set<ConstraintViolation<TotpChallengeRequest>> violations =
        VALIDATOR.validate(new TotpChallengeRequest(""));
    assertFalse(violations.isEmpty());
  }
}
