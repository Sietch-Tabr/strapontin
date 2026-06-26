/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import fr.strapontin.core.QuotaTM;
import fr.strapontin.web.salle.SalleService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
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
  private final SalleService salleService;

  /// @param jdbc         accès JDBC
  /// @param objectMapper désérialiseur JSON (quota TM)
  /// @param restClient   client HTTP partagé (test connexion Digitick)
  /// @param salleService service salle (upsert, mise à jour, déclenchement worker)
  public SourcesService(
      NamedParameterJdbcTemplate jdbc,
      ObjectMapper objectMapper,
      RestClient restClient,
      SalleService salleService) {
    this.jdbc = jdbc;
    this.objectMapper = objectMapper;
    this.restClient = restClient;
    this.salleService = salleService;
  }

  /// Retourne les statuts agrégés de toutes les sources et les deux listes de salles.
  ///
  /// @return map {@code {sources, salles (sans TM ni Eventim), sallesDigitick (avec slug
  // configuré)}}
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
    return salleService.lireIdentifiantsSources(salleUuid);
  }

  /// Crée une salle Digitick dans le référentiel et déclenche immédiatement un fetch.
  ///
  /// @param nom   nom affiché de la salle
  /// @param slug  slug Digitick (ex. {@code "lempreinte"})
  /// @param ville ville (peut être {@code null})
  /// @param cp    code postal 5 chiffres (peut être {@code null})
  /// @return UUID stable de la salle créée ou retrouvée
  public UUID creerSalleDigitick(String nom, String slug, String ville, String cp) {
    return salleService.creerSalleDigitick(nom, slug, ville, cp);
  }

  /// Met à jour les métadonnées d'une salle Digitick (nom, ville, CP).
  ///
  /// @param salleUuid UUID de la salle
  /// @param nom       nouveau nom (null = inchangé)
  /// @param ville     nouvelle ville (null = inchangée)
  /// @param cp        nouveau code postal (null = inchangé)
  public void modifierSalleDigitick(UUID salleUuid, String nom, String ville, String cp) {
    salleService.mettreAJourSalle(salleUuid, nom, ville, cp);
  }

  /// Supprime une salle Digitick si elle n'est suivie par aucun utilisateur et sans concerts.
  ///
  /// @param salleUuid UUID de la salle
  /// @return {@code true} si supprimée, {@code false} si des contraintes bloquent la suppression
  public boolean supprimerSalleDigitick(UUID salleUuid) {
    return salleService.supprimerSalleDigitick(salleUuid);
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

  /// Teste un slug Digitick et retourne le nombre de concerts trouvés.
  ///
  /// <p>Fait un GET sur l'endpoint AJAX Digitick et compte les {@code dl} portant une classe
  /// {@code evenementN} (chiffre immédiatement après "evenement"), même critère que
  /// {@code DigitickSource} avec {@code dl[class~=evenement\\d+]}. Les sous-éléments
  /// ({@code evenementNom}, {@code evenementDate}…) sont exclus car non suivis d'un chiffre.
  /// Retourne -1 si le slug est invalide ou inaccessible.
  ///
  /// @param slug slug à tester
  /// @return nombre de concerts (≥ 0) ou -1 si inaccessible / slug invalide
  public int testerDigitickSlug(String slug) {
    if (slug == null || slug.isBlank()) return -1;
    try {
      byte[] octets = restClient.get().uri(URL_DIGITICK, slug).retrieve().body(byte[].class);
      String html =
          octets != null ? new String(octets, java.nio.charset.StandardCharsets.ISO_8859_1) : "";
      return (int) PATTERN_EVENEMENT.matcher(html).results().count();
    } catch (Exception e) {
      log.debug("Test slug Digitick '{}' — inaccessible : {}", slug, e.getMessage());
      return -1;
    }
  }

  private static final Pattern PATTERN_EVENEMENT = Pattern.compile("evenement\\d");

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
        SELECT s.id, s.nom, s.ville, s.dept, ss.identifiant_externe AS digitick_slug
        FROM salles s
        JOIN salle_sources ss ON ss.salle_id = s.id AND ss.source = 'digitick'
        ORDER BY lower(s.nom)
        """,
        Map.of(),
        (rs, _) ->
            new SalleSourceAdmin(
                rs.getObject("id", UUID.class),
                rs.getString("nom"),
                rs.getString("digitick_slug"),
                rs.getString("ville"),
                rs.getString("dept")));
  }

  private List<SalleSourceAdmin> listerSallesAvecSources() {
    return jdbc.query(
        """
        SELECT s.id,
               s.nom,
               s.ville,
               s.dept,
               MAX(CASE WHEN ss.source = 'digitick' THEN ss.identifiant_externe END) AS digitick_slug
        FROM salles s
        LEFT JOIN salle_sources ss ON ss.salle_id = s.id
        GROUP BY s.id, s.nom, s.ville, s.dept
        HAVING MAX(CASE WHEN ss.source = 'ticketmaster' THEN ss.identifiant_externe END) IS NULL
           AND MAX(CASE WHEN ss.source = 'eventim'      THEN ss.identifiant_externe END) IS NULL
        ORDER BY lower(s.nom)
        """,
        Map.of(),
        (rs, _) ->
            new SalleSourceAdmin(
                rs.getObject("id", UUID.class),
                rs.getString("nom"),
                rs.getString("digitick_slug"),
                rs.getString("ville"),
                rs.getString("dept")));
  }
}
