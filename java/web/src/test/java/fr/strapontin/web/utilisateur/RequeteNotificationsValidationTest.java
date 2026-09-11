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
 * Tests unitaires de validation Bean Validation de {@link RequeteNotifications}.
 *
 * @since 2.8.7
 * @author Kwaite
 */
class RequeteNotificationsValidationTest {

  private static final Validator VALIDATOR =
      Validation.buildDefaultValidatorFactory().getValidator();

  @Test
  void frequenceNotifications_valeurFournie_estValide() {
    Set<ConstraintViolation<RequeteNotifications>> violations =
        VALIDATOR.validate(new RequeteNotifications("quotidien", true, true, false, false));
    assertTrue(violations.isEmpty());
  }

  // sans cette contrainte, FREQUENCES_VALIDES.contains(null) lève une NullPointerException
  // sur un Set.of(...) immuable (UserConfigService) au lieu d'un 400 propre.
  @Test
  void frequenceNotifications_null_estRejete() {
    Set<ConstraintViolation<RequeteNotifications>> violations =
        VALIDATOR.validate(new RequeteNotifications(null, true, true, false, false));
    assertFalse(violations.isEmpty());
  }

  @Test
  void frequenceNotifications_vide_estRejete() {
    Set<ConstraintViolation<RequeteNotifications>> violations =
        VALIDATOR.validate(new RequeteNotifications("", true, true, false, false));
    assertFalse(violations.isEmpty());
  }
}
