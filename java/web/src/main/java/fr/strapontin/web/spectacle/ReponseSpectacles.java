/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.spectacle;

import java.util.List;

/**
 * Réponse paginée de l'endpoint GET /api/spectacles.
 *
 * @since 2.1.0
 * @author Kwaite
 */
public record ReponseSpectacles(
    List<SpectacleDto> concerts,
    int page,
    int totalPages,
    int totalConcerts,
    List<String> genresDisponibles,
    List<String> moisDisponibles,
    List<String> classificationsDisponibles,
    boolean inclureIndisponibles,
    boolean aSuivi) {}
