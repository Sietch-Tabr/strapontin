/*
 * Strapontin — concert finder
 * Copyright (C) 2025 Kwaite
 */
package fr.strapontin.web.push;

/// Réponse de l'endpoint GET /api/push (clé VAPID publique).
///
/// @since 2.1.0
/// @author Kwaite
public record ReponseVapid(boolean disponible, String clePublique) {}
