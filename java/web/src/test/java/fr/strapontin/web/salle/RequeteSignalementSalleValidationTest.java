/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.salle;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Tests unitaires de validation Bean Validation de {@link RequeteSignalementSalle}.
 *
 * @since 2.7.11
 * @author Kwaite
 */
class RequeteSignalementSalleValidationTest {

  private static final Validator VALIDATOR =
      Validation.buildDefaultValidatorFactory().getValidator();

  @Test
  void nomSeul_estValide() {
    final Set<ConstraintViolation<RequeteSignalementSalle>> violations =
        VALIDATOR.validate(new RequeteSignalementSalle("La Cigale", null, null, null));
    assertTrue(violations.isEmpty());
  }

  @Test
  void nom_null_estRejete() {
    final Set<ConstraintViolation<RequeteSignalementSalle>> violations =
        VALIDATOR.validate(new RequeteSignalementSalle(null, "", "", ""));
    assertFalse(violations.isEmpty());
  }

  @Test
  void nom_blanc_estRejete() {
    final Set<ConstraintViolation<RequeteSignalementSalle>> violations =
        VALIDATOR.validate(new RequeteSignalementSalle("   ", "", "", ""));
    assertFalse(violations.isEmpty());
  }

  @Test
  void nom_tropLong_estRejete() {
    final Set<ConstraintViolation<RequeteSignalementSalle>> violations =
        VALIDATOR.validate(new RequeteSignalementSalle("x".repeat(201), "", "", ""));
    assertFalse(violations.isEmpty());
  }

  @Test
  void ville_tropLongue_estRejetee() {
    final Set<ConstraintViolation<RequeteSignalementSalle>> violations =
        VALIDATOR.validate(new RequeteSignalementSalle("Salle", "v".repeat(101), "", ""));
    assertFalse(violations.isEmpty());
  }

  @Test
  void urlSite_tropLongue_estRejetee() {
    final Set<ConstraintViolation<RequeteSignalementSalle>> violations =
        VALIDATOR.validate(new RequeteSignalementSalle("Salle", "", "u".repeat(501), ""));
    assertFalse(violations.isEmpty());
  }

  @Test
  void commentaire_tropLong_estRejete() {
    final Set<ConstraintViolation<RequeteSignalementSalle>> violations =
        VALIDATOR.validate(new RequeteSignalementSalle("Salle", "", "", "c".repeat(1001)));
    assertFalse(violations.isEmpty());
  }
}
