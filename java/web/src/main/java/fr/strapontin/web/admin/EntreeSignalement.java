/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.admin;

import java.time.Instant;

/**
 * Une entrée de la vue unifiée des signalements de matching.
 * @since 2.9.0
 * @author Kwaite
 */
public record EntreeSignalement(
    String categorie,
    String id,
    String type,
    String entiteType,
    String entiteId,
    String confiance,
    Instant detecteLe,
    String details) {}
