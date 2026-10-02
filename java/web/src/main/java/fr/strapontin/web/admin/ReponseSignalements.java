/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.admin;

import java.util.List;

/**
 * Réponse paginée de l'endpoint GET /api/admin/signalements.
 *
 * @param signalements page de signalements actifs
 * @param page numéro de page (0-indexé)
 * @param totalPages nombre total de pages
 * @param total nombre total de signalements actifs
 * @param catalogue nom et explication de chaque type de signalement
 *
 * @since 2.9.0
 * @author Kwaite
 */
public record ReponseSignalements(
    List<EntreeSignalement> signalements,
    int page,
    int totalPages,
    long total,
    List<CatalogueSignalements.TypeSignalement> catalogue) {}
