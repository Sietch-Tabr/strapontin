/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.spectacle;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Tests unitaires de validation Bean Validation de {@link RequeteMasquerArtiste}.
 *
 * @since 2.8.7
 * @author Kwaite
 */
class RequeteMasquerArtisteValidationTest {

  private static final Validator VALIDATOR =
      Validation.buildDefaultValidatorFactory().getValidator();

  @Test
  void artisteId_fourni_estValide() {
    final Set<ConstraintViolation<RequeteMasquerArtiste>> violations =
        VALIDATOR.validate(new RequeteMasquerArtiste("550e8400-e29b-41d4-a716-446655440000"));
    assertTrue(violations.isEmpty());
  }

  @Test
  void artisteId_null_estRejete() {
    final Set<ConstraintViolation<RequeteMasquerArtiste>> violations =
        VALIDATOR.validate(new RequeteMasquerArtiste(null));
    assertFalse(violations.isEmpty());
  }

  @Test
  void artisteId_vide_estRejete() {
    final Set<ConstraintViolation<RequeteMasquerArtiste>> violations =
        VALIDATOR.validate(new RequeteMasquerArtiste(""));
    assertFalse(violations.isEmpty());
  }
}
