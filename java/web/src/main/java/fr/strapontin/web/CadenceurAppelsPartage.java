/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web;

import fr.strapontin.sources.CadenceurAppels;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * Implémentation de {@link CadenceurAppels} adossée à Postgres — coordonne
 * les appels entre web+worker (verrou mémoire insuffisant, deux processus).
 * Réservation atomique par {@code UPDATE ... RETURNING} (verrou de ligne
 * Postgres, sans transaction {@code SERIALIZABLE}).
 * @since 2.8.1
 * @author Kwaite
 */
public final class CadenceurAppelsPartage implements CadenceurAppels {

  private final NamedParameterJdbcTemplate jdbc;
  private final String cle;
  private final long delaiMinMs;

  /**
   * @param jdbc accès à la base partagée entre web et worker
   * @param cle identifiant du service externe cadencé (ex.
   *     {@code "ticketmaster"} )
   * @param delaiMinMs écart minimal en millisecondes entre deux appels
   *     successifs
   */
  public CadenceurAppelsPartage(
      final NamedParameterJdbcTemplate jdbc, final String cle, final long delaiMinMs) {
    this.jdbc = jdbc;
    this.cle = cle;
    this.delaiMinMs = delaiMinMs;
  }

  // Attente calculée par PostgreSQL sur sa seule horloge : la comparer
  // à celle de la JVM la fausserait de leur décalage. La ligne porte le créneau
  // suivant ; l'attente est celle du créneau réservé, donc nulle après une inactivité.
  @Override
  public void attendre() {
    final Long attenteMs =
        jdbc.queryForObject(
            """
            UPDATE cadences_appels_externes
            SET prochain_appel_autorise = GREATEST(prochain_appel_autorise, clock_timestamp())
                + (:delaiMs || ' milliseconds')::interval
            WHERE cle = :cle
            RETURNING CEIL(EXTRACT(EPOCH FROM prochain_appel_autorise
                - (:delaiMs || ' milliseconds')::interval - clock_timestamp()) * 1000)::bigint
            """,
            Map.of("cle", cle, "delaiMs", delaiMinMs),
            Long.class);
    if (attenteMs == null || attenteMs <= 0) {
      return;
    }
    try {
      Thread.sleep(attenteMs);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
