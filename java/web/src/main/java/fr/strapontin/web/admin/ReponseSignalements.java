/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.admin;

import java.util.List;

/**
 * Page de signalements actifs de GET /api/admin/signalements, avec le
 * catalogue des types pour leur affichage en clair.
 * @since 2.9.0
 * @author Kwaite
 */
public record ReponseSignalements(
    List<EntreeSignalement> signalements,
    int page,
    int totalPages,
    long total,
    List<CatalogueSignalements.TypeSignalement> catalogue) {}
