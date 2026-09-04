/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.admin;

import java.util.List;

/// Réponse paginée de l'endpoint GET /api/admin/sources/salles.
///
/// @since 2.8.7
/// @author Kwaite
public record ReponseSallesSources(
    List<SalleSourceAdmin> salles, int page, int totalPages, long total) {}
