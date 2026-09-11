/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.securite;

/**
 * Corps JSON de la requête POST /api/auth/forgot-password.
 *
 * @since 2.1.0
 * @author Kwaite
 */
public record ForgotPasswordRequest(String email) {}
