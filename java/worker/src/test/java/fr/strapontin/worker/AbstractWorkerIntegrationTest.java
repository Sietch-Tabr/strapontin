/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Base commune pour <strong>tous</strong> les tests d'intégration Testcontainers du module worker —
 * un seul conteneur PostgreSQL statique partagé entre les sous-classes plutôt qu'un par classe (le
 * champ statique, hérité, désigne la même instance JVM pour toutes) : l'ancien agent CI (1 Go
 * RAM) a été tué ({@code Killed}, conteneurs Postgres orphelins jamais nettoyés par Ryuk après le
 * kill) quand le module worker en a fait tourner 5 simultanément (2.7.3).
 *
 * <p>Étendu en 2.7.5 à {@code PersisterConcertsIntegrationTest}/ {@code
 * RecalculerMatchesServiceIntegrationTest} — jusque-là volontairement épargnées (stables en CI
 * depuis des mois, périmètre restreint aux classes ajoutées en 2.7.3), mais un OOM équivalent
 * constaté côté module {@code web} (2 conteneurs indépendants suffisent à saturer la même limite de
 * 1 Go) a confirmé qu'aucune classe de ce module ne doit plus déclarer son propre conteneur, sans
 * exception. <strong>Toute nouvelle classe d'intégration Testcontainers du module worker doit
 * étendre cette classe</strong> — ne jamais déclarer un {@code @ServiceConnection static final
 * PostgreSQLContainer} local.
 *
 * <p>Nettoyage commun : un conteneur partagé signifie une base partagée entre toutes les
 * sous-classes du module, exécutées dans un ordre non garanti — le {@code @BeforeEach} local de
 * chaque sous-classe (souvent incomplet, ex. {@code DELETE FROM artistes} sans jamais toucher
 * {@code user_artistes}) ne suffit plus à garantir l'isolement une fois plusieurs classes en jeu
 * sur la même instance PostgreSQL (constaté en CI : une ligne {@code user_artistes} laissée par une
 * classe fait échouer le {@code DELETE FROM artistes} d'une classe suivante, {@code
 * user_artistes.artiste_id} étant {@code NOT NULL} malgré une FK {@code ON DELETE SET NULL} jamais
 * alignée en V24 — incohérence sans impact prod, le code applicatif nettoyant toujours {@code
 * user_artistes} avant de supprimer un artiste). {@code nettoyerBaseCommune} s'exécute avant le
 * {@code @BeforeEach} de chaque sous-classe (ordre garanti par JUnit 5 : superclasse puis
 * sous-classe) et vide toutes les tables via {@code TRUNCATE ... CASCADE}, insensible à ce type
 * d'incohérence de FK (contrairement à {@code DELETE}).
 *
 * @since 2.7.3
 * @author Kwaite
 */
@SpringBootTest
@ActiveProfiles("test")
public abstract class AbstractWorkerIntegrationTest {

  @ServiceConnection
  static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18");

  static {
    postgres.start();
  }

  @Autowired private NamedParameterJdbcTemplate jdbcNettoyage;

  @BeforeEach
  void nettoyerBaseCommune() {
    jdbcNettoyage
        .getJdbcTemplate()
        .execute(
            // concerts est une vue depuis #1192 (evenements+observations) — TRUNCATE porte sur
            // evenements, qui cascade vers observations et user_matches (ON DELETE CASCADE).
            "TRUNCATE TABLE users, artistes, salles, evenements, fetch_jobs,"
                + " fetch_jobs_historique, logs_admin, signalements_matching"
                + " RESTART IDENTITY CASCADE");
  }
}
