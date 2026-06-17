/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.salle;

/// Corps JSON de la requête POST /api/salles/signaler.
///
/// @since 2.2.5
/// @author Kwaite
public record RequeteSignalementSalle(
    String nom, String ville, String urlSite, String commentaire) {}
