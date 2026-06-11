/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.admin;

/// Résultat métier d'une relance manuelle des fetches via le worker.
///
/// Découple la couche service du protocole HTTP : {@code AdminController} traduit
/// ce résultat en réponse HTTP (200 ou 503).
///
/// @param succes        {@code true} si le worker a accepté le déclenchement
/// @param messageErreur message d'erreur si échec, {@code null} si succès
/// @since 2.2.3
/// @author Kwaite
public record ResultatRelance(boolean succes, String messageErreur) {}
