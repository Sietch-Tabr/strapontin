/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.sources;

/// Garantit un écart minimal entre deux appels HTTP vers un même service externe.
///
/// {@code sources} reste volontairement sans dépendance JDBC/DB — {@link CadenceurAppelsMemoire}
/// (implémentation par défaut, verrou en mémoire) ne coordonne que les appels d'un même processus.
/// Un service externe appelé depuis plusieurs processus indépendants (ex. Ticketmaster, appelé à la
/// fois par le worker et par la recherche interactive du module web) nécessite une implémentation
/// portée par l'appelant, adossée à un état partagé (ex. Postgres) — voir les implémentations JDBC
/// des modules {@code web} et {@code worker}.
///
/// @since 2.8.1
/// @author Kwaite
public interface CadenceurAppels {

  /// Bloque l'appelant si nécessaire pour respecter l'écart minimal depuis le dernier appel.
  void attendre();
}
