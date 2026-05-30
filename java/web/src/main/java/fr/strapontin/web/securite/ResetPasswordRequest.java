/*
 * Strapontin — concert finder
 * Copyright (C) 2025 Kwaite
 */
package fr.strapontin.web.securite;

/// Corps JSON de la requête POST /api/auth/reset-password.
///
/// @since 2.1.0
/// @author Kwaite
public record ResetPasswordRequest(String token, String motDePasse) {}
