/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web;

import org.testcontainers.postgresql.PostgreSQLContainer;

/// Base commune pour **tous** les tests d'intégration Testcontainers du module web — un seul
/// conteneur PostgreSQL statique partagé entre les sous-classes plutôt qu'un par classe (le champ
/// statique, hérité, désigne la même instance JVM pour toutes), même motif que
/// {@code fr.strapontin.worker.AbstractWorkerIntegrationTest} (worker, #999, 2.7.3).
///
/// Introduite en 2.7.5 (#1005) : `AdminServiceIntegrationTest` et `SalleRepositoryIntegrationTest`
/// (ajoutées le même jour, 17/07/2026, jamais alignées sur ce pattern) faisaient tourner 2
/// conteneurs Postgres indépendants simultanément — suffisant pour épuiser la limite de 1 Go RAM
/// de l'agent CI Woodpecker (`Killed`, confirmé par `free -h` en direct : swap à 511/512 Mio au
/// moment du kill), le même mécanisme que l'incident worker de 2.7.3 mais jamais corrigé côté
/// web faute d'avoir été touché à ce moment-là. **Toute nouvelle classe d'intégration
/// Testcontainers du module web doit étendre cette classe** — ne jamais déclarer un
/// `static final PostgreSQLContainer` local.
///
/// <p>Pas de {@code @SpringBootTest} ici (contrairement à l'équivalent worker) : les sous-classes
/// actuelles construisent leurs dépendances manuellement (DataSource/Flyway montés à la main) pour
/// éviter la complexité d'un contexte web complet non nécessaire à leur test ciblé — cette classe
/// ne fait que porter le conteneur partagé, sans imposer de contexte Spring.
///
/// @since 2.7.5
/// @author Kwaite
public abstract class AbstractWebIntegrationTest {

  protected static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18");

  static {
    postgres.start();
  }
}
