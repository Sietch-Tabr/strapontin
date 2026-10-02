/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.admin;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Validation de {@link RequeteRepartitionSalle}.
 *
 * @since 3.0.2
 * @author Kwaite
 */
class RequeteRepartitionSalleValidationTest {

  private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

  @Test
  void valider_uneSeuleDestination_valide() {
    assertTrue(
        validator
            .validate(new RequeteRepartitionSalle(List.of("Lieu"), UUID.randomUUID(), null))
            .isEmpty());
    assertTrue(
        validator.validate(new RequeteRepartitionSalle(List.of("Lieu"), null, "Salle")).isEmpty());
  }

  @Test
  void valider_aucuneOuDeuxDestinations_invalide() {
    assertFalse(
        validator.validate(new RequeteRepartitionSalle(List.of("Lieu"), null, " ")).isEmpty());
    assertFalse(
        validator
            .validate(new RequeteRepartitionSalle(List.of("Lieu"), UUID.randomUUID(), "Salle"))
            .isEmpty());
  }

  @Test
  void valider_aucunLibelle_invalide() {
    assertFalse(
        validator.validate(new RequeteRepartitionSalle(List.of(), null, "Salle")).isEmpty());
  }
}
