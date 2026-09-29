/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.admin;

import java.util.UUID;

/**
 * Salle pour la gestion admin des sources — {@code lieuEventim} porte le ou les
 * libellés Eventim interrogés pour cette salle (null si aucun).
 * @since 2.3.0
 * @author Kwaite
 */
public record SalleSourceAdmin(
    UUID salleId, String nom, String lieuEventim, String ville, String dept) {}
