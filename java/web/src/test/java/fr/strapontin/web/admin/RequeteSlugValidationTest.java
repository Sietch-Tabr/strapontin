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
 * Tests unitaires de validation Bean Validation de {@link RequeteSlug}.
 *
 * @since 2.7.11
 * @author Kwaite
 */
class RequeteSlugValidationTest {

  private static final Validator VALIDATOR =
      Validation.buildDefaultValidatorFactory().getValidator();

  @Test
  void slug_null_estValide() {
    // null/vide est légitime : suppression du slug existant.
    final Set<ConstraintViolation<RequeteSlug>> violations =
        VALIDATOR.validate(new RequeteSlug(null));
    assertTrue(violations.isEmpty());
  }

  @Test
  void slug_present_estValide() {
    final Set<ConstraintViolation<RequeteSlug>> violations =
        VALIDATOR.validate(new RequeteSlug("le-splendid"));
    assertTrue(violations.isEmpty());
  }

  @Test
  void slug_tropLong_estRejete() {
    final Set<ConstraintViolation<RequeteSlug>> violations =
        VALIDATOR.validate(new RequeteSlug("a".repeat(101)));
    assertFalse(violations.isEmpty());
  }
}
