/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker.sante;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import fr.strapontin.core.CorrespondanceSalle;
import fr.strapontin.worker.LogsAdminWorker;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Détecte des tokens de {@code salles.nom_norm} suggérant un mot-type
 * générique national manquant (détail : CLAUDE.md, Modèle événement
 * canonique).
 * @since 2.9.0
 * @author Kwaite
 */
@Service
public class SuggestionMotsGenericsService {

  private static final Logger LOG = LoggerFactory.getLogger(SuggestionMotsGenericsService.class);

  private static final String CLE_CONFIG = "MOTS_GENERIQUES_SUGGERES_JSON";

  /**
   * Seuil en occurrences brutes (pas en pourcentage du référentiel, dont le
   * volume total varie trop peu jalon après jalon pour qu'un pourcentage soit
   * discriminant) au-delà duquel un token spécifique est suspecté d'être en
   * réalité un mot-type générique national.
   */
  public static final int SEUIL_OCCURRENCES_MOT_GENERIQUE = 15;

  private final NamedParameterJdbcTemplate jdbc;
  private final LogsAdminWorker logsAdminWorker;
  private final ObjectMapper objectMapper;

  /**
   * @param jdbc accès JDBC
   * @param logsAdminWorker journalisation des mots suggérés
   * @param objectMapper sérialisation de la liste des mots déjà suggérés
   */
  public SuggestionMotsGenericsService(
      final NamedParameterJdbcTemplate jdbc,
      final LogsAdminWorker logsAdminWorker,
      final ObjectMapper objectMapper) {
    this.jdbc = jdbc;
    this.logsAdminWorker = logsAdminWorker;
    this.objectMapper = objectMapper;
  }

  /**
   * Calcule la fréquence de chaque token spécifique parmi toutes les salles et
   * signale ceux dont l'occurrence dépasse
   * {@value #SEUIL_OCCURRENCES_MOT_GENERIQUE} , hors mots déjà suggérés.
   *
   * @return nombre de mots nouvellement suggérés
   */
  public int suggerer() {
    final List<String> noms =
        jdbc.queryForList("SELECT nom_norm FROM salles", Map.of(), String.class);

    final Map<String, Integer> frequences = new HashMap<>();
    for (final String nom : noms) {
      final Set<String> tokens = new HashSet<>(CorrespondanceSalle.tokensSignificatifs(nom));
      tokens.removeAll(CorrespondanceSalle.MOTS_GENERIQUES_SALLE);
      for (final String token : tokens) {
        frequences.merge(token, 1, Integer::sum);
      }
    }

    final Set<String> dejaSuggeres = lireDejaSuggeres();
    int nouveaux = 0;
    for (final Map.Entry<String, Integer> entree : frequences.entrySet()) {
      final String mot = entree.getKey();
      final int occurrences = entree.getValue();
      if (occurrences < SEUIL_OCCURRENCES_MOT_GENERIQUE || dejaSuggeres.contains(mot)) {
        continue;
      }

      dejaSuggeres.add(mot);
      nouveaux++;
      logsAdminWorker.inserer(
          "warn",
          "coherence-salle",
          "mot potentiellement générique suggéré mot=" + mot + " occurrences=" + occurrences,
          Map.of("mot", mot, "occurrences", occurrences));
    }
    if (nouveaux > 0) {
      ecrireDejaSuggeres(dejaSuggeres);
    }

    LOG.info("coherence:mots-generiques candidats={} suggeres={}", frequences.size(), nouveaux);
    return nouveaux;
  }

  private Set<String> lireDejaSuggeres() {
    try {
      final String json =
          jdbc.queryForObject(
              "SELECT valeur_chiffree FROM app_config WHERE cle = :cle",
              Map.of("cle", CLE_CONFIG),
              String.class);
      if (json == null) {
        return new HashSet<>();
      }
      return objectMapper.readValue(json, new TypeReference<>() {});
    } catch (EmptyResultDataAccessException e) {
      return new HashSet<>();
    } catch (JsonProcessingException e) {
      LOG.warn(
          "liste des mots génériques suggérés malformée en base — réinitialisée : {}",
          e.getMessage());
      return new HashSet<>();
    }
  }

  private void ecrireDejaSuggeres(final Set<String> mots) {
    try {
      final String json = objectMapper.writeValueAsString(mots);
      jdbc.update(
          """
          INSERT INTO app_config (cle, valeur_chiffree)
          VALUES (:cle, :json)
          ON CONFLICT (cle) DO UPDATE SET valeur_chiffree = :json, mis_a_jour_le = now()
          """,
          Map.of("cle", CLE_CONFIG, "json", json));
    } catch (JsonProcessingException e) {
      LOG.error("impossible de sérialiser les mots génériques suggérés", e);
    }
  }
}
