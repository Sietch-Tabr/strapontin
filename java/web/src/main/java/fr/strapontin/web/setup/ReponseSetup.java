/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.setup;

import com.fasterxml.jackson.annotation.JsonProperty;

/// Réponse de l'endpoint GET /api/setup.
///
/// @since 2.1.0
/// @author Kwaite
public record ReponseSetup(@JsonProperty("deja_configure") boolean dejaConfiguree) {}
