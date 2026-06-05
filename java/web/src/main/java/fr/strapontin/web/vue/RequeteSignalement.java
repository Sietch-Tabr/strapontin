/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.vue;

/// Corps de la requête de signalement depuis la page d'aide.
///
/// @param type    le type de signalement (bug, suggestion, question)
/// @param message le message de l'utilisateur
/// @since 2.2.0
/// @author Kwaite
public record RequeteSignalement(String type, String message) {}
