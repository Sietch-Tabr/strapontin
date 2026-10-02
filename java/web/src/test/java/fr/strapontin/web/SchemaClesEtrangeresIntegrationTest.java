/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * Vérifie sur le schéma issu des migrations que chaque clé étrangère dispose d'un index dont elle
 * forme le préfixe : sans lui, chaque suppression dans la table référencée parcourt toute la table
 * référençante.
 *
 * @since 3.0.2
 * @author Kwaite
 */
class SchemaClesEtrangeresIntegrationTest extends AbstractWebIntegrationTest {

  private static NamedParameterJdbcTemplate jdbc;

  @BeforeAll
  static void migrerBase() {
    final DriverManagerDataSource dataSource = new DriverManagerDataSource();
    dataSource.setDriverClassName("org.postgresql.Driver");
    dataSource.setUrl(postgres.getJdbcUrl());
    dataSource.setUsername(postgres.getUsername());
    dataSource.setPassword(postgres.getPassword());
    Flyway.configure().dataSource(dataSource).load().migrate();
    jdbc = new NamedParameterJdbcTemplate(dataSource);
  }

  @Test
  void chaqueCleEtrangereEstIndexee() {
    final List<String> nonIndexees =
        jdbc.getJdbcOperations()
            .queryForList(
                """
                SELECT c.conrelid::regclass || '(' || string_agg(a.attname, ',' ORDER BY k.ord) || ')'
                FROM pg_constraint c
                CROSS JOIN LATERAL unnest(c.conkey) WITH ORDINALITY AS k(attnum, ord)
                JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = k.attnum
                WHERE c.contype = 'f'
                  AND c.connamespace = 'public'::regnamespace
                  AND NOT EXISTS (
                    SELECT 1 FROM pg_index i
                    WHERE i.indrelid = c.conrelid
                      AND (i.indkey::int2[])[0:array_length(c.conkey, 1) - 1] @> c.conkey
                      AND (i.indkey::int2[])[0:array_length(c.conkey, 1) - 1] <@ c.conkey)
                GROUP BY c.conrelid, c.conname
                ORDER BY 1
                """,
                String.class);
    assertEquals(List.of(), nonIndexees, "clés étrangères sans index dont elles sont le préfixe");
  }
}
