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
 * Tests unitaires de validation Bean Validation de {@link RequeteDepartements}.
 *
 * @since 2.7.11
 * @author Kwaite
 */
class RequeteDepartementsValidationTest {

  private static final Validator VALIDATOR =
      Validation.buildDefaultValidatorFactory().getValidator();

  @Test
  void departements_null_estValide() {
    final Set<ConstraintViolation<RequeteDepartements>> violations =
        VALIDATOR.validate(new RequeteDepartements(null));
    assertTrue(violations.isEmpty());
  }

  @Test
  void departements_limiteExacte_estValide() {
    final Set<ConstraintViolation<RequeteDepartements>> violations =
        VALIDATOR.validate(new RequeteDepartements("7".repeat(1000)));
    assertTrue(violations.isEmpty());
  }

  @Test
  void departements_tropLong_estRejete() {
    final Set<ConstraintViolation<RequeteDepartements>> violations =
        VALIDATOR.validate(new RequeteDepartements("7".repeat(1001)));
    assertFalse(violations.isEmpty());
  }
}
