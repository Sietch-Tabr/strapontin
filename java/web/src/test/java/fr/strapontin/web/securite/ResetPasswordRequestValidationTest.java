/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.securite;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Tests unitaires de validation Bean Validation de {@link ResetPasswordRequest}.
 *
 * <p>{@code identifiant} reste volontairement sans contrainte : optionnel (flux d'activation
 * d'invitation uniquement), son format n'est validé qu'après normalisation (strip/minuscule) dans
 * {@link AuthController#reinitialiserMotDePasse}.
 *
 * @since 2.7.11
 * @author Kwaite
 */
class ResetPasswordRequestValidationTest {

  private static final Validator VALIDATOR =
      Validation.buildDefaultValidatorFactory().getValidator();

  @Test
  void tokenEtMotDePasseValides_sansIdentifiant_estValide() {
    final Set<ConstraintViolation<ResetPasswordRequest>> violations =
        VALIDATOR.validate(new ResetPasswordRequest("token-abc", "motdepasse12345", null));
    assertTrue(violations.isEmpty());
  }

  @Test
  void token_null_estRejete() {
    final Set<ConstraintViolation<ResetPasswordRequest>> violations =
        VALIDATOR.validate(new ResetPasswordRequest(null, "motdepasse12345", null));
    assertFalse(violations.isEmpty());
  }

  @Test
  void motDePasse_tropCourt_estRejete() {
    final Set<ConstraintViolation<ResetPasswordRequest>> violations =
        VALIDATOR.validate(new ResetPasswordRequest("token-abc", "court", null));
    assertFalse(violations.isEmpty());
  }
}
