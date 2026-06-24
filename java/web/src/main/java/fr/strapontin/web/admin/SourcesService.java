/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import fr.strapontin.core.QuotaTM;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

/// Service de gestion de l'onglet admin « Sources ».
///
/// <p>Agrège les statuts des sources de données et gère la configuration
/// des slugs Digitick par salle.
///
/// @since 2.3.0
/// @author Kwaite
@Service
public class SourcesService {

  private static final Logger log = LoggerFactory.getLogger(SourcesService.class);
  private static final String URL_DIGITICK =
      "https://web.digitick.com/ext/billetterie5/index.php?site={slug}&action=ajax&op=1";

  private final NamedParameterJdbcTemplate jdbc;
  private final ObjectMapper objectMapper;
  private final RestClient restClient;

  /// @param jdbc         accès JDBC
  /// @param objectMapper désérialiseur JSON (quota TM)
  /// @param restClient   client HTTP partagé (test connexion Digitick)
  public SourcesService(
      NamedParameterJdbcTemplate jdbc, ObjectMapper objectMapper, RestClient restClient) {
    this.jdbc = jdbc;
    this.objectMapper = objectMapper;
    this.restClient = restClient;
  }

  /// Retourne les statuts agrégés de toutes les sources et les deux listes de salles.
  ///
  /// @return map {@code {sources, salles (sans TM ni Eventim), sallesDigitick (avec slug configuré)}}
  public Map<String, Object> lireSources() {
    return Map.of(
        "sources", construireSources(),
        "salles", listerSallesAvecSources(),
        "sallesDigitick", listerSallesAvecDigitick());
  }

  /// Retourne le quota TM et l'état du quota pour réutilisation dans d'autres endpoints.
  ///
  /// @return map {@code {quotaTM, etatQuota}} — valeurs null si quota absent
  public Map<String, Object> lireQuotaTM() {
    QuotaTM quota = lireQuotaTMDepuisBase();
    String etat = calculerEtatQuota(quota);
    return Map.of("quotaTM", quota != null ? quota : "null", "etatQuota", etat);
  }

  /// Construit la chaîne d'identifiants préfixés pour une salle (ex. {@code "tm:rZFSnyZdFvA"}).
  ///
  /// @param salleUuid UUID de la salle
  /// @return identifiants CSV ou {@code null} si aucun identifiant connu
  public String obtenirIdentifiantsSalle(UUID salleUuid) {
    List<Map<String, Object>> lignes =
        jdbc.queryForList(
            """
            SELECT source, identifiant_externe
            FROM salle_sources
            WHERE salle_id = CAST(:uuid AS uuid)
            ORDER BY source
            """,
            Map.of("uuid", salleUuid.toString()));
    if (lignes.isEmpty()) return null;
    return lignes.stream()
        .map(l -> l.get("source") + ":" + l.get("identifiant_externe"))
        .reduce((a, b) -> a + "," + b)
        .orElse(null);
  }

  /// Met à jour le slug Digitick d'une salle dans {@code salle_sources}.
  ///
  /// <p>Si le slug est vide, supprime l'entrée existante.
  ///
  /// @param salleUuid UUID de la salle
  /// @param slug      nouveau slug Digitick (peut être vide pour supprimer)
  public void mettreAJourDigitickSlug(UUID salleUuid, String slug) {
    if (slug == null || slug.isBlank()) {
      jdbc.update(
          """
          DELETE FROM salle_sources
          WHERE salle_id = CAST(:uuid AS uuid) AND source = 'digitick'
          """,
          Map.of("uuid", salleUuid.toString()));
    } else {
      jdbc.update(
          """
          INSERT INTO salle_sources (salle_id, source, identifiant_externe)
          VALUES (CAST(:uuid AS uuid), 'digitick', :slug)
          ON CONFLICT (source, identifiant_externe) DO UPDATE SET salle_id = EXCLUDED.salle_id
          """,
          Map.of("uuid", salleUuid.toString(), "slug", slug.strip()));
    }
  }

  /// Teste l'accessibilité d'un slug Digitick via un HEAD HTTP.
  ///
  /// @param slug slug à tester
  /// @return {@code true} si HTTP 2xx, {@code false} sinon ou en cas d'erreur réseau
  public boolean testerDigitickSlug(String slug) {
    if (slug == null || slug.isBlank()) return false;
    try {
      restClient.head().uri(URL_DIGITICK, slug).retrieve().toBodilessEntity();
      return true;
    } catch (Exception e) {
      log.debug("Test slug Digitick '{}' — inaccessible : {}", slug, e.getMessage());
      return false;
    }
  }

  private List<StatutSource> construireSources() {
    Instant dernierFetch = lireDernierFetch();
    QuotaTM quotaTM = lireQuotaTMDepuisBase();
    String etatQuota = calculerEtatQuota(quotaTM);
    int nbDigitick = compterSallesDigitick();

    List<StatutSource> sources = new ArrayList<>();
    sources.add(
        new StatutSource(
            "ticketmaster", "Ticketmaster", "concerts", quotaTM, etatQuota, dernierFetch, null));
    sources.add(new StatutSource("eventim", "Eventim", "concerts", null, null, dernierFetch, null));
    sources.add(new StatutSource("deezer", "Deezer", "metadonnees", null, null, null, null));
    sources.add(new StatutSource("digitick", "Digitick", "concerts", null, null, null, nbDigitick));
    return sources;
  }

  private QuotaTM lireQuotaTMDepuisBase() {
    List<String> quotaJson =
        jdbc.queryForList(
            "SELECT valeur_chiffree FROM app_config WHERE cle = 'TM_QUOTA_JSON'",
            Map.of(),
            String.class);
    if (quotaJson.isEmpty()) return null;
    try {
      return objectMapper.readValue(quotaJson.get(0), QuotaTM.class);
    } catch (Exception e) {
      log.warn("Quota TM malformé — ignoré");
      return null;
    }
  }

  private String calculerEtatQuota(QuotaTM quota) {
    if (quota == null || quota.total() == 0) return "ok";
    int pct = quota.dispo() * 100 / quota.total();
    return pct < 10 ? "critique" : pct < 20 ? "attention" : "ok";
  }

  private Instant lireDernierFetch() {
    try {
      java.sql.Timestamp ts =
          jdbc.queryForObject(
              "SELECT MAX(derniere_exec) FROM fetch_jobs WHERE erreur IS NULL OR erreur = ''",
              Map.of(),
              java.sql.Timestamp.class);
      return ts != null ? ts.toInstant() : null;
    } catch (Exception e) {
      log.debug("Impossible de lire le dernier fetch : {}", e.getMessage());
      return null;
    }
  }

  private int compterSallesDigitick() {
    try {
      Integer nb =
          jdbc.queryForObject(
              "SELECT COUNT(*) FROM salle_sources WHERE source = 'digitick'",
              Map.of(),
              Integer.class);
      return nb != null ? nb : 0;
    } catch (Exception e) {
      log.debug("Impossible de compter les salles Digitick : {}", e.getMessage());
      return 0;
    }
  }

  private List<SalleSourceAdmin> listerSallesAvecDigitick() {
    return jdbc.query(
        """
        SELECT s.id, s.nom, ss.identifiant_externe AS digitick_slug
        FROM salles s
        JOIN salle_sources ss ON ss.salle_id = s.id AND ss.source = 'digitick'
        ORDER BY lower(s.nom)
        """,
        Map.of(),
        (rs, _) ->
            new SalleSourceAdmin(
                rs.getObject("id", UUID.class),
                rs.getString("nom"),
                rs.getString("digitick_slug")));
  }

  private List<SalleSourceAdmin> listerSallesAvecSources() {
    return jdbc.query(
        """
        SELECT s.id,
               s.nom,
               MAX(CASE WHEN ss.source = 'digitick' THEN ss.identifiant_externe END) AS digitick_slug
        FROM salles s
        LEFT JOIN salle_sources ss ON ss.salle_id = s.id
        GROUP BY s.id, s.nom
        HAVING MAX(CASE WHEN ss.source = 'ticketmaster' THEN ss.identifiant_externe END) IS NULL
           AND MAX(CASE WHEN ss.source = 'eventim'      THEN ss.identifiant_externe END) IS NULL
        ORDER BY lower(s.nom)
        """,
        Map.of(),
        (rs, _) ->
            new SalleSourceAdmin(
                rs.getObject("id", UUID.class),
                rs.getString("nom"),
                rs.getString("digitick_slug")));
  }
}
