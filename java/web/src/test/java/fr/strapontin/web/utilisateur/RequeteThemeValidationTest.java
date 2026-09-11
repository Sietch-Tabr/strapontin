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
 * Tests unitaires de validation Bean Validation de {@link RequeteTheme}.
 *
 * @since 2.8.7
 * @author Kwaite
 */
class RequeteThemeValidationTest {

  private static final Validator VALIDATOR =
      Validation.buildDefaultValidatorFactory().getValidator();

  @Test
  void valeur_fournie_estValide() {
    final Set<ConstraintViolation<RequeteTheme>> violations =
        VALIDATOR.validate(new RequeteTheme("sombre"));
    assertTrue(violations.isEmpty());
  }

  // sans cette contrainte, THEMES_VALIDES.contains(null) lève une NullPointerException
  // sur un Set.of(...) immuable (UserConfigService) au lieu d'un 400 propre.
  @Test
  void valeur_null_estRejete() {
    final Set<ConstraintViolation<RequeteTheme>> violations = VALIDATOR.validate(new RequeteTheme(null));
    assertFalse(violations.isEmpty());
  }

  @Test
  void valeur_vide_estRejete() {
    final Set<ConstraintViolation<RequeteTheme>> violations = VALIDATOR.validate(new RequeteTheme(""));
    assertFalse(violations.isEmpty());
  }
}
