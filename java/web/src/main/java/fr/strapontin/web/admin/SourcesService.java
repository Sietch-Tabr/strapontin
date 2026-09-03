/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import fr.strapontin.core.QuotaTM;
import fr.strapontin.web.ErreurMetier;
import fr.strapontin.web.salle.SalleService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
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
/// <p>Agrège les statuts des sources de données et gère la configuration des slugs Digitick par
/// salle.
///
/// @since 2.3.0
/// @author Kwaite
@Service
public class SourcesService {

  private static final Logger log = LoggerFactory.getLogger(SourcesService.class);
  private static final String URL_DIGITICK =
      "https://web.digitick.com/ext/billetterie5/index.php?site={slug}&action=ajax&op=1";
  private static final int TAILLE_PAGE_SALLES = 50;

  private final NamedParameterJdbcTemplate jdbc;
  private final ObjectMapper objectMapper;
  private final RestClient restClient;
  private final SalleService salleService;

  /// @param jdbc accès JDBC
  /// @param objectMapper désérialiseur JSON (quota TM)
  /// @param restClient client HTTP partagé (test connexion Digitick)
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

  /// Retourne les statuts agrégés de toutes les sources.
  ///
  /// <p>Les deux listes de salles (sans TM ni Eventim / avec Digitick) sont paginées séparément via
  /// {@link #listerSalles}, désormais consommées par un endpoint dédié.
  ///
  /// @return map {@code {sources}}
  public Map<String, Object> lireSources() {
    return Map.of("sources", construireSources());
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
  /// @param salleId UUID de la salle
  /// @return identifiants CSV ou {@code null} si aucun identifiant connu
  public String obtenirIdentifiantsSalle(UUID salleId) {
    return salleService.lireIdentifiantsSources(salleId);
  }

  /// Crée une salle Digitick dans le référentiel et déclenche immédiatement un fetch.
  ///
  /// @param nom nom affiché de la salle
  /// @param slug slug Digitick (ex. {@code "lempreinte"})
  /// @param ville ville (peut être {@code null})
  /// @param cp code postal 5 chiffres (peut être {@code null})
  /// @return UUID stable de la salle créée ou retrouvée
  public UUID creerSalleDigitick(String nom, String slug, String ville, String cp) {
    return salleService.creerSalleDigitick(nom, slug, ville, cp);
  }

  /// Met à jour les métadonnées d'une salle Digitick (nom, ville, CP).
  ///
  /// @param salleId UUID de la salle
  /// @param nom nouveau nom (null = inchangé)
  /// @param ville nouvelle ville (null = inchangée)
  /// @param cp nouveau code postal (null = inchangé)
  public void modifierSalleDigitick(UUID salleId, String nom, String ville, String cp) {
    salleService.mettreAJourSalle(salleId, nom, ville, cp);
  }

  /// Supprime une salle Digitick si elle n'est suivie par aucun utilisateur et sans concerts.
  ///
  /// @param salleId UUID de la salle
  /// @return {@code true} si supprimée, {@code false} si des contraintes bloquent la suppression
  public boolean supprimerSalleDigitick(UUID salleId) {
    return salleService.supprimerSalleDigitick(salleId);
  }

  /// Met à jour le slug Digitick d'une salle dans {@code salle_sources}.
  ///
  /// <p>Si le slug est vide, supprime l'entrée existante. Si le slug est déjà attribué à une autre
  /// salle, lève une erreur 409 plutôt que de le voler silencieusement à cette autre salle le
  /// conflit est vérifié avant toute suppression pour ne pas perdre la configuration existante si
  /// l'opération échoue.
  ///
  /// @param salleId UUID de la salle
  /// @param slug nouveau slug Digitick (peut être vide pour supprimer)
  /// @throws ErreurMetier 409 si le slug est déjà attribué à une autre salle
  public void mettreAJourDigitickSlug(UUID salleId, String slug) {
    String slugStrip = slug == null ? "" : slug.strip();
    if (!slugStrip.isEmpty()) {
      verifierSlugDigitickLibre(salleId, slugStrip);
    }
    jdbc.update(
        """
        DELETE FROM salle_sources
        WHERE salle_id = CAST(:uuid AS uuid) AND source = 'digitick'
        """,
        Map.of("uuid", salleId.toString()));
    if (!slugStrip.isEmpty()) {
      jdbc.update(
          """
          INSERT INTO salle_sources (salle_id, source, identifiant_externe)
          VALUES (CAST(:uuid AS uuid), 'digitick', :slug)
          ON CONFLICT (source, identifiant_externe) DO UPDATE SET salle_id = EXCLUDED.salle_id
          """,
          Map.of("uuid", salleId.toString(), "slug", slugStrip));
    }
  }

  /// Vérifie qu'un slug Digitick n'est pas déjà attribué à une autre salle que {@code salleId}.
  ///
  /// @param salleId salle pour laquelle le slug est en cours d'attribution
  /// @param slug slug à vérifier
  /// @throws ErreurMetier 409 si le slug est déjà utilisé par une autre salle
  private void verifierSlugDigitickLibre(UUID salleId, String slug) {
    List<Map<String, Object>> conflits =
        jdbc.queryForList(
            """
            SELECT COALESCE(s.nom_affiche, s.nom) AS nom
            FROM salle_sources ss
            JOIN salles s ON s.id = ss.salle_id
            WHERE ss.source = 'digitick' AND ss.identifiant_externe = :slug
              AND ss.salle_id != CAST(:uuid AS uuid)
            """,
            Map.of("slug", slug, "uuid", salleId.toString()));
    if (!conflits.isEmpty()) {
      throw new ErreurMetier(
          "Slug déjà utilisé par la salle " + conflits.get(0).get("nom") + ".", 409);
    }
  }

  /// Teste un slug Digitick et retourne le nombre de concerts trouvés.
  ///
  /// <p>Fait un GET sur l'endpoint AJAX Digitick et compte les {@code dl} portant une classe {@code
  /// evenementN} (chiffre immédiatement après "evenement"), même critère que {@code DigitickSource}
  /// avec {@code dl[class~=evenement\\d+]}. Les sous-éléments ({@code evenementNom}, {@code
  /// evenementDate}…) sont exclus car non suivis d'un chiffre. Retourne -1 si le slug est invalide
  /// ou inaccessible.
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

    List<StatutSource> sources = new ArrayList<>();
    sources.add(
        new StatutSource(
            "ticketmaster",
            "Ticketmaster",
            "concerts",
            quotaTM,
            etatQuota,
            dernierFetch,
            compterSallesParSource("ticketmaster"),
            compterArtistesParSource("ticketmaster")));
    sources.add(
        new StatutSource(
            "eventim",
            "Eventim",
            "concerts",
            null,
            null,
            dernierFetch,
            compterSallesParSource("eventim"),
            compterArtistesParSource("eventim")));
    sources.add(
        new StatutSource(
            "deezer", "Deezer", "metadonnees", null, null, null, null, compterArtistesDeezer()));
    sources.add(
        new StatutSource(
            "digitick",
            "Digitick",
            "concerts",
            null,
            null,
            null,
            compterSallesParSource("digitick"),
            null));
    sources.add(
        new StatutSource(
            "wikidata",
            "Wikidata",
            "metadonnees",
            null,
            null,
            null,
            compterSallesWikidata(),
            compterArtistesWikidata()));
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

  /// Compte les salles reliées à une source externe donnée.
  ///
  /// @param source identifiant de la source (ex. {@code "ticketmaster"}, {@code "digitick"})
  /// @return nombre de salles avec un identifiant pour cette source, 0 en cas d'échec
  private Integer compterSallesParSource(String source) {
    return compterAvecFallback(
        "SELECT COUNT(*) FROM salle_sources WHERE source = :source",
        Map.of("source", source),
        "les salles pour la source " + source);
  }

  /// Compte les artistes reliés à une source externe donnée.
  ///
  /// @param source identifiant de la source (ex. {@code "ticketmaster"})
  /// @return nombre d'artistes avec un identifiant pour cette source, 0 en cas d'échec
  private Integer compterArtistesParSource(String source) {
    return compterAvecFallback(
        "SELECT COUNT(*) FROM artiste_sources WHERE source = :source",
        Map.of("source", source),
        "les artistes pour la source " + source);
  }

  /// Compte les artistes avec un identifiant Deezer — colonne directe sur {@code artistes}, pas
  /// d'entrée dans {@code artiste_sources} pour cette source.
  ///
  /// @return nombre d'artistes avec {@code deezer_id} renseigné, 0 en cas d'échec
  private Integer compterArtistesDeezer() {
    return compterAvecFallback(
        "SELECT COUNT(*) FROM artistes WHERE deezer_id IS NOT NULL",
        Map.of(),
        "les artistes Deezer");
  }

  private Integer compterSallesWikidata() {
    return compterAvecFallback(
        "SELECT COUNT(*) FROM salles WHERE wikidata_id IS NOT NULL",
        Map.of(),
        "les salles Wikidata");
  }

  private Integer compterArtistesWikidata() {
    return compterAvecFallback(
        "SELECT COUNT(*) FROM artistes WHERE wikidata_id IS NOT NULL",
        Map.of(),
        "les artistes Wikidata");
  }

  /// Exécute un {@code COUNT(*)} avec repli sur 0 en cas d'échec — motif partagé par les 5 méthodes
  /// de comptage ci-dessus, jusqu'ici dupliqué à l'identique dans chacune.
  ///
  /// @param sql requête {@code SELECT COUNT(*)} paramétrée
  /// @param params paramètres nommés de la requête
  /// @param descriptionEchec description de ce qui est compté, pour le message de log en cas
  ///   d'échec
  /// @return le compte, ou 0 en cas d'échec
  private Integer compterAvecFallback(
      String sql, Map<String, Object> params, String descriptionEchec) {
    try {
      Integer nb = jdbc.queryForObject(sql, params, Integer.class);
      return nb != null ? nb : 0;
    } catch (Exception e) {
      log.debug("Impossible de compter {} : {}", descriptionEchec, e.getMessage());
      return 0;
    }
  }

  /// Liste paginée des salles pour l'onglet admin « Sources ».
  ///
  /// @param digitick {@code true} pour les salles déjà configurées avec un slug Digitick, {@code
  ///   false} pour celles sans identifiant Ticketmaster ni Eventim (candidates à une configuration
  ///   manuelle)
  /// @param page numéro de page (0-indexé)
  /// @param nom filtre nom partiel (insensible à la casse), peut être null
  /// @return page de résultats
  public ReponseSallesSources listerSalles(boolean digitick, int page, String nom) {
    return digitick ? listerSallesAvecDigitick(page, nom) : listerSallesSansTmNiEventim(page, nom);
  }

  private ReponseSallesSources listerSallesAvecDigitick(int page, String nom) {
    int pageNum = Math.max(0, page);
    StringBuilder where = new StringBuilder();
    Map<String, Object> params = new HashMap<>();
    if (nom != null && !nom.isBlank()) {
      where.append(" AND s.nom ILIKE :nom ");
      params.put("nom", "%" + nom.strip() + "%");
    }
    params.put("taille", TAILLE_PAGE_SALLES);
    params.put("offset", pageNum * TAILLE_PAGE_SALLES);
    long[] total = {0};
    List<SalleSourceAdmin> salles =
        jdbc.query(
            "SELECT s.id, s.nom, s.ville, s.dept, ss.identifiant_externe AS digitick_slug,"
                + " COUNT(*) OVER() AS total_filtre"
                + " FROM salles s"
                + " JOIN salle_sources ss ON ss.salle_id = s.id AND ss.source = 'digitick'"
                + " WHERE 1=1 "
                + where
                + " ORDER BY lower(s.nom) LIMIT :taille OFFSET :offset",
            params,
            (rs, _) -> {
              total[0] = rs.getLong("total_filtre");
              return new SalleSourceAdmin(
                  rs.getObject("id", UUID.class),
                  rs.getString("nom"),
                  rs.getString("digitick_slug"),
                  rs.getString("ville"),
                  rs.getString("dept"));
            });

    if (salles.isEmpty() && pageNum > 0) {
      total[0] =
          compterAvecFallback(
              "SELECT COUNT(*) FROM salles s JOIN salle_sources ss ON ss.salle_id = s.id AND"
                  + " ss.source = 'digitick' WHERE 1=1 "
                  + where,
              params,
              "salles avec Digitick");
    }

    int totalPages = Math.max(1, (int) Math.ceil((double) total[0] / TAILLE_PAGE_SALLES));
    return new ReponseSallesSources(salles, pageNum, totalPages, total[0]);
  }

  private ReponseSallesSources listerSallesSansTmNiEventim(int page, String nom) {
    int pageNum = Math.max(0, page);
    StringBuilder where = new StringBuilder();
    Map<String, Object> params = new HashMap<>();
    if (nom != null && !nom.isBlank()) {
      where.append(" AND s.nom ILIKE :nom ");
      params.put("nom", "%" + nom.strip() + "%");
    }
    params.put("taille", TAILLE_PAGE_SALLES);
    params.put("offset", pageNum * TAILLE_PAGE_SALLES);
    long[] total = {0};
    List<SalleSourceAdmin> salles =
        jdbc.query(
            "SELECT s.id, s.nom, s.ville, s.dept,"
                + " MAX(CASE WHEN ss.source = 'digitick' THEN ss.identifiant_externe END) AS"
                + " digitick_slug, COUNT(*) OVER() AS total_filtre"
                + " FROM salles s"
                + " LEFT JOIN salle_sources ss ON ss.salle_id = s.id"
                + " WHERE 1=1 "
                + where
                + " GROUP BY s.id, s.nom, s.ville, s.dept"
                + " HAVING MAX(CASE WHEN ss.source = 'ticketmaster' THEN ss.identifiant_externe"
                + " END) IS NULL AND MAX(CASE WHEN ss.source = 'eventim' THEN"
                + " ss.identifiant_externe END) IS NULL"
                + " ORDER BY lower(s.nom) LIMIT :taille OFFSET :offset",
            params,
            (rs, _) -> {
              total[0] = rs.getLong("total_filtre");
              return new SalleSourceAdmin(
                  rs.getObject("id", UUID.class),
                  rs.getString("nom"),
                  rs.getString("digitick_slug"),
                  rs.getString("ville"),
                  rs.getString("dept"));
            });

    // COUNT(*) OVER() porte ici sur les lignes post-HAVING (fenêtre appliquée après le filtrage
    // par groupe), donc valide même avec ce HAVING — seul le repli page-vide doit le reproduire.
    if (salles.isEmpty() && pageNum > 0) {
      Long totalVide =
          jdbc.queryForObject(
              "SELECT COUNT(*) FROM (SELECT s.id"
                  + " FROM salles s"
                  + " LEFT JOIN salle_sources ss ON ss.salle_id = s.id"
                  + " WHERE 1=1 "
                  + where
                  + " GROUP BY s.id"
                  + " HAVING MAX(CASE WHEN ss.source = 'ticketmaster' THEN ss.identifiant_externe"
                  + " END) IS NULL AND MAX(CASE WHEN ss.source = 'eventim' THEN"
                  + " ss.identifiant_externe END) IS NULL) sous_requete",
              params,
              Long.class);
      total[0] = totalVide != null ? totalVide : 0;
    }

    int totalPages = Math.max(1, (int) Math.ceil((double) total[0] / TAILLE_PAGE_SALLES));
    return new ReponseSallesSources(salles, pageNum, totalPages, total[0]);
  }
}
