/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.admin;

import java.util.List;

/// Réponse paginée de l'endpoint GET /api/admin/salles.
///
/// @since 2.6.0
/// @author Kwaite
public record ReponseReferentielSalles(
    List<SalleReferentiel> salles, int page, int totalPages, long totalSalles) {}
