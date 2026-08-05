/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.artiste;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.util.Set;
import org.junit.jupiter.api.Test;

/// Tests unitaires de validation Bean Validation de {@link RequeteAjouter}.
///
/// <p>Le {@code wikidataId} est concaténé dans un appel API Wikidata sortant
/// ({@link ArtisteService#resoudreDeezerId}) — sans contrainte de format, une valeur arbitraire
/// pouvait y transiter.
///
/// @since 2.6.3
/// @author Kwaite
class RequeteAjouterValidationTest {

  private static final Validator VALIDATOR =
      Validation.buildDefaultValidatorFactory().getValidator();

  @Test
  void wikidataId_null_estValide() {
    Set<ConstraintViolation<RequeteAjouter>> violations =
        VALIDATOR.validate(new RequeteAjouter("Radiohead", null));
    assertTrue(violations.isEmpty());
  }

  @Test
  void wikidataId_formatQIdValide_estValide() {
    Set<ConstraintViolation<RequeteAjouter>> violations =
        VALIDATOR.validate(new RequeteAjouter("Radiohead", "Q167023"));
    assertTrue(violations.isEmpty());
  }

  // une valeur arbitraire (ex. tentative d'injection dans la requête Wikidata sortante)
  // doit être rejetée par Bean Validation avant d'atteindre le service.
  @Test
  void wikidataId_formatInvalide_estRejete() {
    Set<ConstraintViolation<RequeteAjouter>> violations =
        VALIDATOR.validate(new RequeteAjouter("Radiohead", "Q1 . } SELECT ?x"));
    assertFalse(violations.isEmpty());
  }

  @Test
  void wikidataId_sansPrefixeQ_estRejete() {
    Set<ConstraintViolation<RequeteAjouter>> violations =
        VALIDATOR.validate(new RequeteAjouter("Radiohead", "167023"));
    assertFalse(violations.isEmpty());
  }
}
