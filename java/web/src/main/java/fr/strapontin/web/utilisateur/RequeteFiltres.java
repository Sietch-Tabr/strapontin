/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.utilisateur;

/// Corps JSON de la requête PATCH /api/user/config/filtres.
///
/// @since 2.1.0
/// @author Kwaite
public record RequeteFiltres(boolean filtreDisponibles) {}
