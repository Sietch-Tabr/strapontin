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
 * Tests unitaires de validation Bean Validation de {@link RequeteNomAffiche}.
 *
 * @since 2.7.11
 * @author Kwaite
 */
class RequeteNomAfficheValidationTest {

  private static final Validator VALIDATOR =
      Validation.buildDefaultValidatorFactory().getValidator();

  @Test
  void nomAffiche_null_estValide() {
    // null est la valeur légitime pour revenir au nom automatique.
    final Set<ConstraintViolation<RequeteNomAffiche>> violations =
        VALIDATOR.validate(new RequeteNomAffiche(null));
    assertTrue(violations.isEmpty());
  }

  @Test
  void nomAffiche_present_estValide() {
    final Set<ConstraintViolation<RequeteNomAffiche>> violations =
        VALIDATOR.validate(new RequeteNomAffiche("Zénith de Nantes Métropole"));
    assertTrue(violations.isEmpty());
  }

  @Test
  void nomAffiche_tropLong_estRejete() {
    final Set<ConstraintViolation<RequeteNomAffiche>> violations =
        VALIDATOR.validate(new RequeteNomAffiche("a".repeat(201)));
    assertFalse(violations.isEmpty());
  }
}
