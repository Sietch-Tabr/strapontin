/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.utilisateur;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Tests unitaires de validation Bean Validation de {@link RequeteEmailChange}.
 *
 * @since 2.7.11
 * @author Kwaite
 */
class RequeteEmailChangeValidationTest {

  private static final Validator VALIDATOR =
      Validation.buildDefaultValidatorFactory().getValidator();

  @Test
  void email_formatValide_estValide() {
    final Set<ConstraintViolation<RequeteEmailChange>> violations =
        VALIDATOR.validate(new RequeteEmailChange("bob@test.fr"));
    assertTrue(violations.isEmpty());
  }

  @Test
  void email_null_estRejete() {
    final Set<ConstraintViolation<RequeteEmailChange>> violations =
        VALIDATOR.validate(new RequeteEmailChange(null));
    assertFalse(violations.isEmpty());
  }

  @Test
  void email_formatInvalide_estRejete() {
    final Set<ConstraintViolation<RequeteEmailChange>> violations =
        VALIDATOR.validate(new RequeteEmailChange("pas-un-email"));
    assertFalse(violations.isEmpty());
  }

  @Test
  void email_tropLong_estRejete() {
    final String local = "a".repeat(250);
    final Set<ConstraintViolation<RequeteEmailChange>> violations =
        VALIDATOR.validate(new RequeteEmailChange(local + "@test.fr"));
    assertFalse(violations.isEmpty());
  }
}
