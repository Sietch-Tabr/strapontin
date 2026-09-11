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
 * Tests unitaires de validation Bean Validation de {@link RequeteDigitickSalle}.
 *
 * <p>Le caractère obligatoire de {@code nom}/{@code slug} est vérifié séparément par chaque
 * contrôleur (diffère entre création et modification) — ce record ne porte que des bornes.
 *
 * @since 2.7.11
 * @author Kwaite
 */
class RequeteDigitickSalleValidationTest {

  private static final Validator VALIDATOR =
      Validation.buildDefaultValidatorFactory().getValidator();

  @Test
  void tousLesChampsNull_estValide() {
    Set<ConstraintViolation<RequeteDigitickSalle>> violations =
        VALIDATOR.validate(new RequeteDigitickSalle(null, null, null, null));
    assertTrue(violations.isEmpty());
  }

  @Test
  void tousLesChampsRenseignes_estValide() {
    Set<ConstraintViolation<RequeteDigitickSalle>> violations =
        VALIDATOR.validate(new RequeteDigitickSalle("Salle X", "salle-x", "Nancy", "54"));
    assertTrue(violations.isEmpty());
  }

  @Test
  void nom_tropLong_estRejete() {
    Set<ConstraintViolation<RequeteDigitickSalle>> violations =
        VALIDATOR.validate(new RequeteDigitickSalle("a".repeat(201), null, null, null));
    assertFalse(violations.isEmpty());
  }

  @Test
  void dept_tropLong_estRejete() {
    Set<ConstraintViolation<RequeteDigitickSalle>> violations =
        VALIDATOR.validate(new RequeteDigitickSalle(null, null, null, "9999"));
    assertFalse(violations.isEmpty());
  }
}
