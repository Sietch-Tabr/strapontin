/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.admin;

/// Quota Ticketmaster stocké en JSON dans {@code app_config}.
///
/// @since 2.1.0
/// @author Kwaite
public record QuotaTM(int dispo, int total, long reset, String misAJour) {}
