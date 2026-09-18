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
 * Tests unitaires de validation Bean Validation de {@link RequeteBanniere}.
 *
 * @since 2.6.6
 * @author Kwaite
 */
class RequeteBanniereValidationTest {

  private static final Validator VALIDATOR =
      Validation.buildDefaultValidatorFactory().getValidator();

  @Test
  void message_vide_estRejete() {
    final Set<ConstraintViolation<RequeteBanniere>> violations =
        VALIDATOR.validate(new RequeteBanniere("", "info"));
    assertFalse(violations.isEmpty());
  }

  @Test
  void message_longueurExactement500_estValide() {
    final Set<ConstraintViolation<RequeteBanniere>> violations =
        VALIDATOR.validate(new RequeteBanniere("a".repeat(500), "info"));
    assertTrue(violations.isEmpty());
  }

  @Test
  void message_longueur501_estRejete() {
    final Set<ConstraintViolation<RequeteBanniere>> violations =
        VALIDATOR.validate(new RequeteBanniere("a".repeat(501), "info"));
    assertFalse(violations.isEmpty());
  }
}
