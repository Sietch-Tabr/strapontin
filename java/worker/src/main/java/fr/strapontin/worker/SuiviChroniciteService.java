/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker;

import fr.strapontin.core.AccesAppConfig;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Suit, par clé (ex. "fetch:salles:all|Ticketmaster"), le nombre de
 * cycles consécutifs en échec — détail (persistance, seuil chronique) :
 * CLAUDE.md, Jobs background.
 * @since 2.8.2
 * @author Kwaite
 */
@Service
public class SuiviChroniciteService {

  private static final Logger LOG = LoggerFactory.getLogger(SuiviChroniciteService.class);

  private static final String CLE_CONFIG = "FIABILITE_STREAKS_JSON";

  /**
   * Nombre de cycles consécutifs en échec à partir duquel un message est
   * qualifié de chronique plutôt que traité comme un warn isolé — 3 couvre
   * aussi bien une cadence à 11 cycles/jour (Ticketmaster) qu'une cadence à 1
   * cycle/nuit (enrichissement, réconciliation nocturne).
   */
  public static final int SEUIL_CYCLES_CHRONIQUE = 3;

  private final NamedParameterJdbcTemplate jdbc;
  private final AccesAppConfig acces;
  private final ObjectMapper objectMapper;

  /**
   * @param jdbc accès JDBC
   * @param objectMapper sérialisation des compteurs de série en JSON
   */
  public SuiviChroniciteService(
      final NamedParameterJdbcTemplate jdbc, final ObjectMapper objectMapper) {
    this.jdbc = jdbc;
    this.acces = new AccesAppConfig(jdbc);
    this.objectMapper = objectMapper;
  }

  /**
   * Enregistre le résultat du cycle courant : incrémente la série si en
   * échec, la remet à zéro sinon.
   * @param cle identifiant stable du couple job/source
   * @param cycleEnEchec true si ce cycle a rencontré au moins une erreur
   * @return cycles consécutifs en échec après cet enregistrement (0 si propre)
   */
  public int enregistrerCycle(final String cle, final boolean cycleEnEchec) {
    final Map<String, Integer> streaks = lireStreaks();
    if (!cycleEnEchec) {
      if (streaks.remove(cle) != null) {
        ecrireStreaks(streaks);
      }
      return 0;
    }
    final int nouveau = streaks.getOrDefault(cle, 0) + 1;
    streaks.put(cle, nouveau);
    ecrireStreaks(streaks);
    return nouveau;
  }

  /**
   * Complète un message {@code logs_admin} par une mention de chronicité
   * dès {@value #SEUIL_CYCLES_CHRONIQUE} cycles consécutifs en échec.
   * @param message message d'origine
   * @param streak cycles consécutifs en échec (voir {@link #enregistrerCycle})
   * @return le message complété si chronique, inchangé sinon
   */
  public String enrichirSiChronique(final String message, final int streak) {
    if (streak < SEUIL_CYCLES_CHRONIQUE) {
      return message;
    }
    return message + " — chronique depuis " + streak + " cycles consécutifs";
  }

  private Map<String, Integer> lireStreaks() {
    try {
      final Optional<String> json = acces.lire(CLE_CONFIG);
      if (json.isEmpty()) {
        return new HashMap<>();
      }
      return objectMapper.readValue(json.get(), new TypeReference<>() {});
    } catch (JacksonException e) {
      LOG.warn("streaks de chronicité malformés en base — réinitialisés : {}", e.getMessage());
      return new HashMap<>();
    }
  }

  private void ecrireStreaks(final Map<String, Integer> streaks) {
    try {
      final String json = objectMapper.writeValueAsString(streaks);
      acces.ecrire(CLE_CONFIG, json);
    } catch (JacksonException e) {
      LOG.error("impossible de sérialiser les streaks de chronicité", e);
    }
  }
}
