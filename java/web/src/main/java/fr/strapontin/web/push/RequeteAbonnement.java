/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.push;

/// Corps JSON de la requête POST /api/push (abonnement push).
///
/// @since 2.1.0
/// @author Kwaite
public record RequeteAbonnement(String endpoint, ClesAbonnement keys) {}
