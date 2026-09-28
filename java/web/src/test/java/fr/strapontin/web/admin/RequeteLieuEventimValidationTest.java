/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.admin;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

/**
 * Tests de validation Bean Validation de {@link RequeteLieuEventim}.
 *
 * @since 3.0.1
 * @author Kwaite
 */
class RequeteLieuEventimValidationTest {

  private static final Validator VALIDATOR =
      Validation.buildDefaultValidatorFactory().getValidator();

  @Test
  void libelle_null_estValide() {
    // null/vide est légitime : retrait du lieu configuré.
    assertTrue(VALIDATOR.validate(new RequeteLieuEventim(null)).isEmpty());
  }

  @Test
  void libelle_present_estValide() {
    assertTrue(
        VALIDATOR.validate(new RequeteLieuEventim("LEMPREINTE SAVIGNY LE TEMPLE CEDEX")).isEmpty());
  }

  @Test
  void libelle_tropLong_estRejete() {
    assertFalse(VALIDATOR.validate(new RequeteLieuEventim("a".repeat(201))).isEmpty());
  }
}
