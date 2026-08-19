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

/// Tests unitaires de validation Bean Validation de {@link RequeteAbonnement}.
///
/// @since 2.7.11
/// @author Kwaite
class RequeteAbonnementValidationTest {

  private static final Validator VALIDATOR =
      Validation.buildDefaultValidatorFactory().getValidator();

  private static RequeteAbonnement requeteValide() {
    return new RequeteAbonnement("https://push.exemple/abc", new ClesAbonnement("p256dh", "auth"));
  }

  @Test
  void requeteComplete_estValide() {
    assertTrue(VALIDATOR.validate(requeteValide()).isEmpty());
  }

  @Test
  void endpoint_null_estRejete() {
    Set<ConstraintViolation<RequeteAbonnement>> violations =
        VALIDATOR.validate(new RequeteAbonnement(null, new ClesAbonnement("p256dh", "auth")));
    assertFalse(violations.isEmpty());
  }

  @Test
  void keys_null_estRejete() {
    Set<ConstraintViolation<RequeteAbonnement>> violations =
        VALIDATOR.validate(new RequeteAbonnement("https://push.exemple/abc", null));
    assertFalse(violations.isEmpty());
  }

  @Test
  void keysP256dh_vide_estRejete() {
    Set<ConstraintViolation<RequeteAbonnement>> violations =
        VALIDATOR.validate(
            new RequeteAbonnement("https://push.exemple/abc", new ClesAbonnement("", "auth")));
    assertFalse(violations.isEmpty());
  }

  @Test
  void endpoint_tropLong_estRejete() {
    Set<ConstraintViolation<RequeteAbonnement>> violations =
        VALIDATOR.validate(
            new RequeteAbonnement("a".repeat(2049), new ClesAbonnement("p256dh", "auth")));
    assertFalse(violations.isEmpty());
  }
}
