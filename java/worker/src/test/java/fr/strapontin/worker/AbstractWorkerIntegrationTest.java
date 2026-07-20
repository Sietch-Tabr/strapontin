/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.postgresql.PostgreSQLContainer;

/// Base commune pour **tous** les tests d'intégration Testcontainers du module worker — un seul
/// conteneur PostgreSQL statique partagé entre les sous-classes plutôt qu'un par classe (le champ
/// statique, hérité, désigne la même instance JVM pour toutes) : l'agent CI Woodpecker (1 Go RAM)
/// a été tué (`Killed`, conteneurs Postgres orphelins jamais nettoyés par Ryuk après le kill)
/// quand le module worker en a fait tourner 5 simultanément (2.7.3).
///
/// Étendu en 2.7.5 (#1005) à `PersisterConcertsIntegrationTest`/
/// `RecalculerMatchesServiceIntegrationTest` — jusque-là volontairement épargnées (stables en CI
/// depuis des mois, périmètre restreint aux classes ajoutées en 2.7.3), mais un OOM équivalent
/// constaté côté module `web` (2 conteneurs indépendants suffisent à saturer la même limite de
/// 1 Go) a confirmé qu'aucune classe de ce module ne doit plus déclarer son propre conteneur,
/// sans exception. **Toute nouvelle classe d'intégration Testcontainers du module worker doit
/// étendre cette classe** — ne jamais déclarer un `@ServiceConnection static final
/// PostgreSQLContainer` local.
///
/// @since 2.7.3
/// @author Kwaite
@SpringBootTest
@ActiveProfiles("test")
public abstract class AbstractWorkerIntegrationTest {

  @ServiceConnection
  static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18");

  static {
    postgres.start();
  }
}
