/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.admin;

import fr.strapontin.core.QuotaTM;
import java.time.Instant;

/**
 * Statut agrégé d'une source de données pour l'onglet admin Sources.
 * @since 2.3.0
 * @author Kwaite
 */
public record StatutSource(
    String id,
    String nom,
    String type,
    QuotaTM quotaTM,
    String etatQuota,
    Instant dernierFetch,
    Integer nbSallesConfigurees,
    Integer nbArtistesConfigures) {}
