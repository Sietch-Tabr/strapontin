/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.core;

/// Quota d'API Ticketmaster extrait des headers HTTP Rate-Limit-*.
///
/// Type partagé entre les modules `sources` (extraction depuis les headers),
/// `worker` (persistance dans `app_config`) et `web` (affichage admin).
///
/// @param dispo nombre de requêtes restantes dans la fenêtre courante
/// @param total limite totale par fenêtre
/// @param reset timestamp Unix (ms) de réinitialisation du quota
/// @param misAJour horodatage ISO 8601 de la dernière mise à jour
/// @since 2.2.3
/// @author Kwaite
public record QuotaTM(int dispo, int total, long reset, String misAJour) {}
