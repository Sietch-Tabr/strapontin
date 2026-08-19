/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.securite;

import jakarta.validation.constraints.NotBlank;

/// Corps de la requête de vérification du challenge TOTP.
///
/// @param code le code à 6 chiffres saisi par l'utilisateur
/// @since 2.2.0
/// @author Kwaite
public record TotpChallengeRequest(@NotBlank(message = "Le code TOTP est requis.") String code) {}
