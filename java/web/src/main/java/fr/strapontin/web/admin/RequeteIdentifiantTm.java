/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.admin;

import jakarta.validation.constraints.NotBlank;

/// Corps JSON de la requête PATCH de correction directe de l'identifiant Ticketmaster d'un artiste.
///
/// @since 2.7.7
/// @author Kwaite
public record RequeteIdentifiantTm(
    @NotBlank(message = "Identifiant Ticketmaster requis.") String identifiantExterne) {}
