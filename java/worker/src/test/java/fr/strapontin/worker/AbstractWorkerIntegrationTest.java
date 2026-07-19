/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.postgresql.PostgreSQLContainer;

/// Base commune pour les tests d'intégration Testcontainers du module worker introduits en
/// 2.7.3 — un seul conteneur PostgreSQL statique partagé entre les sous-classes plutôt qu'un par
/// classe (le champ statique, hérité, désigne la même instance JVM pour toutes) : l'agent CI
/// Woodpecker (1 Go RAM) a été tué (`Killed`, conteneurs Postgres orphelins jamais nettoyés par
/// Ryuk après le kill) quand le module worker en a fait tourner 5 simultanément.
///
/// N'affecte volontairement pas `PersisterConcertsIntegrationTest`/
/// `RecalculerMatchesServiceIntegrationTest` (stables en CI depuis des mois, chacune son propre
/// conteneur) — périmètre restreint aux classes ajoutées en 2.7.3.
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
