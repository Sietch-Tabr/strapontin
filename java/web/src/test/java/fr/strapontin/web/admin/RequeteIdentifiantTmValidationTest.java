/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.admin;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Tests unitaires de validation Bean Validation de {@link RequeteIdentifiantTm}.
 *
 * @since 2.7.11
 * @author Kwaite
 */
class RequeteIdentifiantTmValidationTest {

  private static final Validator VALIDATOR =
      Validation.buildDefaultValidatorFactory().getValidator();

  @Test
  void identifiantExterne_present_estValide() {
    Set<ConstraintViolation<RequeteIdentifiantTm>> violations =
        VALIDATOR.validate(new RequeteIdentifiantTm("K8vZ917G1B7"));
    assertTrue(violations.isEmpty());
  }

  @Test
  void identifiantExterne_null_estRejete() {
    Set<ConstraintViolation<RequeteIdentifiantTm>> violations =
        VALIDATOR.validate(new RequeteIdentifiantTm(null));
    assertFalse(violations.isEmpty());
  }

  @Test
  void identifiantExterne_vide_estRejete() {
    Set<ConstraintViolation<RequeteIdentifiantTm>> violations =
        VALIDATOR.validate(new RequeteIdentifiantTm("  "));
    assertFalse(violations.isEmpty());
  }
}
