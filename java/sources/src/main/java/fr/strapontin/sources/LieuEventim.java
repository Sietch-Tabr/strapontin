/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.sources;

/**
 * Lieu tel qu'Eventim le décrit sur un produit — libellé et ville exacts requis
 * par ses filtres {@code venue_names}/{@code city_names}.
 *
 * @param identifiant identifiant interne ({@code internal:<nom normalisé>}),
 *     tel qu'enregistré dans {@code salle_sources}
 * @param libelle nom exact du lieu chez Eventim
 * @param ville ville exacte chez Eventim, ou null
 * @param latitude latitude du lieu, ou null
 * @param longitude longitude du lieu, ou null
 * @since 3.0.1
 * @author Kwaite
 */
public record LieuEventim(
    String identifiant, String libelle, String ville, Double latitude, Double longitude) {}
