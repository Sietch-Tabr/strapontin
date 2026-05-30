/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.admin;

/// Statut d'une clé de configuration applicative.
///
/// @since 2.1.0
/// @author Kwaite
public record ConfigStatut(String cle, boolean presente, String valeur, boolean secret) {}
