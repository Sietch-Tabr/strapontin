/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.push;

import jakarta.validation.constraints.NotBlank;

/// Corps JSON de la requête DELETE /api/push (désabonnement push).
///
/// @since 2.1.0
/// @author Kwaite
public record RequeteDesabonnement(@NotBlank(message = "Endpoint manquant.") String endpoint) {}
