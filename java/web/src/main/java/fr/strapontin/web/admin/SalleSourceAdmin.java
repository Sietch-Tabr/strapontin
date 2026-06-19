/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.admin;

import java.util.UUID;

/// Salle avec ses identifiants sources pour l'onglet admin Sources.
///
/// @param salleUuid   UUID stable de la salle
/// @param nom         nom affiché
/// @param tmVenueId   identifiant Ticketmaster (null si aucun)
/// @param digitickSlug slug Digitick (null si non configuré)
/// @since 2.3.0
/// @author Kwaite
public record SalleSourceAdmin(UUID salleUuid, String nom, String tmVenueId, String digitickSlug) {}
