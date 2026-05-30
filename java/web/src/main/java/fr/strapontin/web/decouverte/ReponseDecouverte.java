/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.decouverte;

import java.time.Instant;
import java.util.List;

/// Réponse paginée de l'endpoint GET /api/decouverte.
///
/// @since 2.1.0
/// @author Kwaite
public record ReponseDecouverte(
    List<ConcertDecouverte> concerts,
    int page,
    int totalPages,
    int totalConcerts,
    List<String> genresDisponibles,
    List<String> classificationsDisponibles,
    List<String> moisDisponibles,
    List<String> deptsUtilisateur,
    Instant derniereSyncRegion,
    List<SyncDept> syncParDept) {}
