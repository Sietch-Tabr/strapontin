/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.sources.eventim;

/**
 * Lieu tel qu'Eventim le décrit sur un produit (libellé et ville exacts de
 * ses filtres venue_names/city_names), champs auto-descriptifs.
 * @since 3.0.1
 * @author Kwaite
 */
public record LieuEventim(
    String identifiant, String libelle, String ville, Double latitude, Double longitude) {}
