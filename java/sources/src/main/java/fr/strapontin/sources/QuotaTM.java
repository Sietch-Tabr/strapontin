/*
 * Strapontin — concert finder
 * Copyright (C) 2025 Kwaite
 */
package fr.strapontin.sources;

/// Quota d'API Ticketmaster extrait des headers HTTP Rate-Limit-*.
///
/// @param dispo nombre de requêtes restantes dans la fenêtre courante
/// @param total limite totale par fenêtre
/// @param reset timestamp Unix (ms) de réinitialisation du quota
/// @param misAJour horodatage ISO 8601 de la dernière mise à jour
/// @since 2.0.0
/// @author Kwaite
public record QuotaTM(int dispo, int total, long reset, String misAJour) {}
