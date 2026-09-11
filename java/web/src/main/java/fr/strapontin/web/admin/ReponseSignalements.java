/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.admin;

import java.util.List;

/**
 * Réponse paginée de l'endpoint GET /api/admin/signalements.
 *
 * @since 2.9.0
 * @author Kwaite
 */
public record ReponseSignalements(
    List<EntreeSignalement> signalements, int page, int totalPages, long total) {}
