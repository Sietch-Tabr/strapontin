/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.admin;

import java.util.UUID;

/**
 * Salle pour la gestion admin des sources Digitick.
 * @since 2.3.0
 * @author Kwaite
 */
public record SalleSourceAdmin(
    UUID salleId, String nom, String digitickSlug, String ville, String dept) {}
