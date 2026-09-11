/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.push;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Tests unitaires de validation Bean Validation de {@link RequeteDesabonnement}.
 *
 * @since 2.7.11
 * @author Kwaite
 */
class RequeteDesabonnementValidationTest {

  private static final Validator VALIDATOR =
      Validation.buildDefaultValidatorFactory().getValidator();

  @Test
  void endpoint_present_estValide() {
    Set<ConstraintViolation<RequeteDesabonnement>> violations =
        VALIDATOR.validate(new RequeteDesabonnement("https://push.exemple/abc"));
    assertTrue(violations.isEmpty());
  }

  @Test
  void endpoint_null_estRejete() {
    Set<ConstraintViolation<RequeteDesabonnement>> violations =
        VALIDATOR.validate(new RequeteDesabonnement(null));
    assertFalse(violations.isEmpty());
  }

  @Test
  void endpoint_blanc_estRejete() {
    Set<ConstraintViolation<RequeteDesabonnement>> violations =
        VALIDATOR.validate(new RequeteDesabonnement("   "));
    assertFalse(violations.isEmpty());
  }
}
