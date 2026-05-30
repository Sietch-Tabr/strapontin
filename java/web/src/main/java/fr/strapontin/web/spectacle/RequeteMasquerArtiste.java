/*
 * Strapontin — concert finder
 * Copyright (C) 2025 Kwaite
 */
package fr.strapontin.web.spectacle;

/// Corps JSON de la requête POST /api/spectacles/masquer-artiste.
///
/// @since 2.1.0
/// @author Kwaite
public record RequeteMasquerArtiste(String artisteNorm) {}
