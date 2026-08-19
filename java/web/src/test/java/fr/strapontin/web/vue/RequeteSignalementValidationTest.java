/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.vue;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.util.Set;
import org.junit.jupiter.api.Test;

/// Tests unitaires de validation Bean Validation de {@link RequeteSignalement}.
///
/// @since 2.7.11
/// @author Kwaite
class RequeteSignalementValidationTest {

  private static final Validator VALIDATOR =
      Validation.buildDefaultValidatorFactory().getValidator();

  @Test
  void messageValide_estValide() {
    Set<ConstraintViolation<RequeteSignalement>> violations =
        VALIDATOR.validate(new RequeteSignalement("bug", "message test"));
    assertTrue(violations.isEmpty());
  }

  @Test
  void message_null_estRejete() {
    Set<ConstraintViolation<RequeteSignalement>> violations =
        VALIDATOR.validate(new RequeteSignalement("bug", null));
    assertFalse(violations.isEmpty());
  }

  @Test
  void message_blanc_estRejete() {
    Set<ConstraintViolation<RequeteSignalement>> violations =
        VALIDATOR.validate(new RequeteSignalement("bug", "   "));
    assertFalse(violations.isEmpty());
  }

  @Test
  void message_tropLong_estRejete() {
    Set<ConstraintViolation<RequeteSignalement>> violations =
        VALIDATOR.validate(new RequeteSignalement("bug", "a".repeat(2001)));
    assertFalse(violations.isEmpty());
  }

  @Test
  void type_inconnu_nEstPasRejeteParBeanValidation() {
    // Le repli sur "question" pour un type inconnu est une décision métier du contrôleur, pas
    // une contrainte structurelle — voir AideRestController#signaler.
    Set<ConstraintViolation<RequeteSignalement>> violations =
        VALIDATOR.validate(new RequeteSignalement("pas-un-type", "message"));
    assertTrue(violations.isEmpty());
  }
}
