/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.push;

/// Clés cryptographiques d'un abonnement Web Push.
///
/// @since 2.1.0
/// @author Kwaite
public record ClesAbonnement(String p256dh, String auth) {}
