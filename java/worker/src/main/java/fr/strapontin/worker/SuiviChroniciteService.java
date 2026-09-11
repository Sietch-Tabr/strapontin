/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Suit, pour une clé donnée (ex. {@code "fetch:salles:all|Ticketmaster"}), le nombre de cycles
 * consécutifs en échec partiel ou total — persisté dans {@code app_config} (même mécanisme que
 * {@code GestionQuotaTM}/{@code TM_QUOTA_JSON}, survit aux redémarrages) plutôt qu'en mémoire.
 *
 * Un taux de 429 Ticketmaster ou d'échec Overpass répété à quasiment chaque cycle avait été
 * documenté à plusieurs reprises comme un événement isolé/ponctuel, faute d'un signal automatique
 * distinguant une occurrence isolée d'une dégradation persistante — seul un dépouillement manuel
 * de plusieurs jours de {@code logs_admin} avait permis de la caractériser.
 *
 * @since 2.8.2
 * @author Kwaite
 */
@Service
public class SuiviChroniciteService {

  private static final Logger log = LoggerFactory.getLogger(SuiviChroniciteService.class);

  private static final String CLE_CONFIG = "FIABILITE_STREAKS_JSON";

  /**
   * Nombre de cycles consécutifs en échec à partir duquel un message est qualifié de chronique
   * plutôt que traité comme un warn isolé — 3 couvre aussi bien une cadence à 11 cycles/jour
   * (Ticketmaster) qu'une cadence à 1 cycle/nuit (Overpass, réconciliation nocturne).
   */
  public static final int SEUIL_CYCLES_CHRONIQUE = 3;

  private final NamedParameterJdbcTemplate jdbc;
  private final ObjectMapper objectMapper;

  public SuiviChroniciteService(
      final NamedParameterJdbcTemplate jdbc, final ObjectMapper objectMapper) {
    this.jdbc = jdbc;
    this.objectMapper = objectMapper;
  }

  /**
   * Enregistre le résultat du cycle courant pour {@code cle} : incrémente le compteur de série si
   * en échec, le remet à zéro sinon.
   *
   * @param cle identifiant stable du couple job/source (ex. {@code
   *   "fetch:salles:all|Ticketmaster"} ou {@code "reconciliation:nocturne|Overpass"})
   * @param cycleEnEchec {@code true} si ce cycle a rencontré au moins une erreur
   * @return le nombre de cycles consécutifs en échec après cet enregistrement (0 si ce cycle est
   *   propre)
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
   * Complète un message {@code logs_admin} par une mention explicite de chronicité quand le nombre
   * de cycles consécutifs en échec atteint {@value #SEUIL_CYCLES_CHRONIQUE}, plutôt que de le
   * laisser identique à chaque cycle comme s'il s'agissait d'un événement isolé.
   *
   * @param message message d'origine
   * @param streak nombre de cycles consécutifs en échec (voir {@link #enregistrerCycle})
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
      final String json =
          jdbc.queryForObject(
              "SELECT valeur_chiffree FROM app_config WHERE cle = :cle",
              Map.of("cle", CLE_CONFIG),
              String.class);
      if (json == null) {
        return new HashMap<>();
      }
      return objectMapper.readValue(json, new TypeReference<HashMap<String, Integer>>() {});
    } catch (EmptyResultDataAccessException e) {
      return new HashMap<>();
    } catch (JsonProcessingException e) {
      log.warn("streaks de chronicité malformés en base — réinitialisés : {}", e.getMessage());
      return new HashMap<>();
    }
  }

  private void ecrireStreaks(final Map<String, Integer> streaks) {
    try {
      final String json = objectMapper.writeValueAsString(streaks);
      jdbc.update(
          """
          INSERT INTO app_config (cle, valeur_chiffree)
          VALUES (:cle, :json)
          ON CONFLICT (cle) DO UPDATE SET valeur_chiffree = :json, mis_a_jour_le = now()
          """,
          Map.of("cle", CLE_CONFIG, "json", json));
    } catch (JsonProcessingException e) {
      log.error("impossible de sérialiser les streaks de chronicité", e);
    }
  }
}
