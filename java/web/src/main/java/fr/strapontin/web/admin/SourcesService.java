/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.admin;

import fr.strapontin.core.Normalisation;
import fr.strapontin.core.QuotaTM;
import fr.strapontin.sources.eventim.EventimSource;
import fr.strapontin.web.ErreurMetier;
import fr.strapontin.web.salle.SalleService;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * Service de gestion de l'onglet admin « Sources » — agrège les statuts
 * des sources de données et gère le lieu Eventim interrogé par salle
 * (Eventim n'expose aucun identifiant de lieu, seulement son nom exact).
 * @since 2.3.0
 * @author Kwaite
 */
@Service
public class SourcesService {

  private static final Logger LOG = LoggerFactory.getLogger(SourcesService.class);
  private static final String CLE_UUID = "uuid";
  private static final String SOURCE_TM = "ticketmaster";
  private static final String SOURCE_EVENTIM = "eventim";

  private final NamedParameterJdbcTemplate jdbc;
  private final ObjectMapper objectMapper;
  private final EventimSource eventim;
  private final SalleService salleService;

  /**
   * @param jdbc accès JDBC
   * @param objectMapper désérialiseur JSON (quota TM)
   * @param eventim client Eventim (test d'un lieu saisi à la main)
   * @param salleService service salle (upsert, mise à jour, déclenchement worker)
   */
  public SourcesService(
      final NamedParameterJdbcTemplate jdbc,
      final ObjectMapper objectMapper,
      final EventimSource eventim,
      final SalleService salleService) {
    this.jdbc = jdbc;
    this.objectMapper = objectMapper;
    this.eventim = eventim;
    this.salleService = salleService;
  }

  /**
   * Retourne les statuts agrégés de toutes les sources.
   * @return map {@code {sources}}
   */
  public Map<String, Object> lireSources() {
    return Map.of("sources", construireSources());
  }

  /**
   * Retourne le quota TM et l'état du quota pour réutilisation dans d'autres
   * endpoints.
   *
   * @return map {@code {quotaTM, etatQuota}} — valeurs null si quota absent
   */
  public Map<String, Object> lireQuotaTM() {
    final QuotaTM quota = lireQuotaTMDepuisBase();
    final String etat = calculerEtatQuota(quota);
    return Map.of("quotaTM", quota != null ? quota : "null", "etatQuota", etat);
  }

  /**
   * Construit la chaîne d'identifiants préfixés pour une salle (ex.
   * {@code "tm:rZFSnyZdFvA"} ).
   *
   * @param salleId UUID de la salle
   * @return identifiants CSV ou {@code null} si aucun identifiant connu
   */
  public String obtenirIdentifiantsSalle(final UUID salleId) {
    return salleService.lireIdentifiantsSources(salleId);
  }

  /**
   * Définit le lieu Eventim d'une salle, avec l'identifiant interne que
   * produit l'ingestion d'un concert de ce lieu.
   * @param salleId UUID de la salle
   * @param libelle nom exact du lieu chez Eventim, vide pour retirer
   * @throws ErreurMetier 409 si le lieu est déjà rattaché à une autre salle
   */
  public void mettreAJourLieuEventim(final UUID salleId, final String libelle) {
    final String libelleStrip = libelle == null ? "" : libelle.strip();
    if (libelleStrip.isEmpty()) {
      jdbc.update(
          "UPDATE salle_sources SET libelle_source = NULL"
              + " WHERE salle_id = CAST(:uuid AS uuid) AND source = 'eventim'",
          Map.of(CLE_UUID, salleId.toString()));
      return;
    }
    final String identifiant =
        "internal:" + Normalisation.normaliserTexte(Normalisation.normaliserCasse(libelleStrip));
    final List<String> conflits =
        jdbc.queryForList(
            """
            SELECT COALESCE(s.nom_affiche, s.nom)
            FROM salle_sources ss JOIN salles s ON s.id = ss.salle_id
            WHERE ss.source = 'eventim' AND ss.identifiant_externe = :identifiant
              AND ss.salle_id != CAST(:uuid AS uuid)
            """,
            Map.of("identifiant", identifiant, CLE_UUID, salleId.toString()),
            String.class);
    if (!conflits.isEmpty()) {
      throw new ErreurMetier("Lieu Eventim déjà rattaché à la salle " + conflits.get(0) + ".", 409);
    }
    jdbc.update(
        """
        INSERT INTO salle_sources (salle_id, source, identifiant_externe, libelle_source)
        VALUES (CAST(:uuid AS uuid), 'eventim', :identifiant, :libelle)
        ON CONFLICT (source, identifiant_externe)
        DO UPDATE SET libelle_source = EXCLUDED.libelle_source
        """,
        Map.of(CLE_UUID, salleId.toString(), "identifiant", identifiant, "libelle", libelleStrip));
  }

  /**
   * Teste un lieu Eventim : nombre de concerts à venir sous ce nom exact.
   * @param libelle nom du lieu à tester
   * @return nombre de concerts (≥ 0), ou -1 si vide ou Eventim injoignable
   */
  public int testerLieuEventim(final String libelle) {
    return eventim.compterConcertsLieu(libelle);
  }

  private List<StatutSource> construireSources() {
    final QuotaTM quotaTM = lireQuotaTMDepuisBase();
    final String etatQuota = calculerEtatQuota(quotaTM);

    final List<StatutSource> sources = new ArrayList<>();
    sources.add(
        new StatutSource(
            SOURCE_TM,
            "Ticketmaster",
            "concerts",
            quotaTM,
            etatQuota,
            lireDernierPassage(SOURCE_TM),
            compterSallesParSource(SOURCE_TM),
            compterArtistesParSource(SOURCE_TM),
            null));
    sources.add(
        new StatutSource(
            SOURCE_EVENTIM,
            "Eventim",
            "concerts",
            null,
            null,
            lireDernierPassage(SOURCE_EVENTIM),
            compterSallesParSource(SOURCE_EVENTIM),
            compterArtistesParSource(SOURCE_EVENTIM),
            null));
    sources.addAll(construireSourcesSitesSalles());
    sources.addAll(construireSourcesMetadonnees());
    return sources;
  }

  private List<StatutSource> construireSourcesMetadonnees() {
    return List.of(
        new StatutSource(
            "deezer",
            "Deezer",
            "metadonnees",
            null,
            null,
            null,
            null,
            compterArtistesDeezer(),
            null),
        new StatutSource(
            "wikidata",
            "Wikidata",
            "metadonnees",
            null,
            null,
            null,
            compterSallesWikidata(),
            compterArtistesWikidata(),
            null));
  }

  private QuotaTM lireQuotaTMDepuisBase() {
    final List<String> quotaJson =
        jdbc.queryForList(
            "SELECT valeur_chiffree FROM app_config WHERE cle = 'TM_QUOTA_JSON'",
            Map.of(),
            String.class);
    if (quotaJson.isEmpty()) {
      return null;
    }
    try {
      return objectMapper.readValue(quotaJson.get(0), QuotaTM.class).enVigueur(Instant.now());
    } catch (Exception e) {
      LOG.warn("Quota TM malformé — ignoré");
      return null;
    }
  }

  private String calculerEtatQuota(final QuotaTM quota) {
    if (quota == null || quota.total() == 0) {
      return "ok";
    }
    final int pct = quota.dispo() * 100 / quota.total();
    return pct < 10 ? "critique" : pct < 20 ? "attention" : "ok";
  }

  // Dernière observation mise à jour par la source, propre à chacune (le dernier
  // job réussi est commun à toutes les sources de concerts).
  private Instant lireDernierPassage(final String source) {
    try {
      return jdbc.queryForObject(
          "SELECT MAX(updated_at) FROM observations WHERE source = CAST(:source AS source_concert)",
          Map.of("source", source),
          Instant.class);
    } catch (Exception e) {
      LOG.debug("Impossible de lire le dernier passage de {} : {}", source, e.getMessage());
      return null;
    }
  }

  // Un site se reconnaît au préfixe de l'identifiant de ses salles : une nouvelle
  // source apparaît dès sa première salle.
  private List<StatutSource> construireSourcesSitesSalles() {
    final boolean jobEnErreur = dernierJobSitesEnErreur();
    return jdbc.query(
        """
        SELECT sites.site, count(DISTINCT sites.salle_id) AS nb_salles,
               (SELECT max(o.updated_at)
                FROM observations o
                JOIN evenements e ON e.id = o.evenement_id
                JOIN salle_sources s2 ON s2.salle_id = e.salle_id AND s2.source = 'site_salle'
                WHERE o.source = 'site_salle'
                  AND split_part(s2.identifiant_externe, ':', 1) = sites.site) AS dernier_passage
        FROM (SELECT split_part(identifiant_externe, ':', 1) AS site, salle_id
              FROM salle_sources WHERE source = 'site_salle') sites
        GROUP BY sites.site
        ORDER BY sites.site
        """,
        Map.of(),
        (rs, _) -> {
          final Instant dernier =
              rs.getTimestamp("dernier_passage") != null
                  ? rs.getTimestamp("dernier_passage").toInstant()
                  : null;
          final String site = rs.getString("site");
          return new StatutSource(
              "site_salle:" + site,
              nomSiteSalle(site),
              "concerts",
              null,
              null,
              dernier,
              rs.getInt("nb_salles"),
              null,
              etatSite(jobEnErreur, dernier));
        });
  }

  private static String nomSiteSalle(final String site) {
    return switch (site) {
      case "triton" -> "Le Triton";
      case "emb" -> "EMB Sannois";
      case "usine" -> "Usine à Chapeaux";
      case "allmol" -> "Allmol Tickets";
      default -> site.isEmpty() ? site : Character.toUpperCase(site.charAt(0)) + site.substring(1);
    };
  }

  // Lus une fois par jour : sans observation depuis trois jours, ou après un dernier
  // passage en erreur, un site est à examiner.
  private static String etatSite(final boolean jobEnErreur, final Instant dernier) {
    final boolean silencieux =
        dernier == null || dernier.isBefore(Instant.now().minus(Duration.ofDays(3)));
    return jobEnErreur || silencieux ? "erreur" : "ok";
  }

  private boolean dernierJobSitesEnErreur() {
    try {
      final List<String> erreurs =
          jdbc.queryForList(
              "SELECT coalesce(erreur, '') FROM fetch_jobs WHERE type_job = 'fetch:sites-salles'",
              Map.of(),
              String.class);
      return !erreurs.isEmpty() && !erreurs.get(0).isEmpty();
    } catch (Exception e) {
      LOG.debug("Impossible de lire le dernier passage des sites : {}", e.getMessage());
      return false;
    }
  }

  /**
   * Compte les salles reliées à une source externe donnée.
   *
   * @param source identifiant de la source (ex. {@code "ticketmaster"} ,
   *     {@code "eventim"} )
   * @return nombre de salles avec un identifiant pour cette source, 0 en cas
   *     d'échec
   */
  private Integer compterSallesParSource(final String source) {
    return compterAvecFallback(
        "SELECT COUNT(*) FROM salle_sources WHERE source = :source",
        Map.of("source", source),
        "les salles pour la source " + source);
  }

  /**
   * Compte les artistes reliés à une source externe donnée.
   *
   * @param source identifiant de la source (ex. {@code "ticketmaster"} )
   * @return nombre d'artistes avec un identifiant pour cette source, 0 en cas
   *     d'échec
   */
  private Integer compterArtistesParSource(final String source) {
    return compterAvecFallback(
        "SELECT COUNT(*) FROM artiste_sources WHERE source = :source",
        Map.of("source", source),
        "les artistes pour la source " + source);
  }

  /**
   * Compte les artistes avec un identifiant Deezer — colonne directe sur
   * {@code artistes} , pas d'entrée dans {@code artiste_sources} pour cette
   * source.
   *
   * @return nombre d'artistes avec {@code deezer_id} renseigné, 0 en cas
   *     d'échec
   */
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

  /**
   * Exécute un {@code COUNT(*)} avec repli sur 0 en cas d'échec — motif
   * partagé par les méthodes de comptage ci-dessus, dupliqué jusqu'ici.
   * @param sql requête {@code SELECT COUNT(*)} paramétrée
   * @param params paramètres nommés de la requête
   * @param descriptionEchec description de ce qui est compté (log si échec)
   * @return le compte, ou 0 en cas d'échec
   */
  private Integer compterAvecFallback(
      final String sql, final Map<String, Object> params, final String descriptionEchec) {
    try {
      final Integer nb = jdbc.queryForObject(sql, params, Integer.class);
      return nb != null ? nb : 0;
    } catch (Exception e) {
      LOG.debug("Impossible de compter {} : {}", descriptionEchec, e.getMessage());
      return 0;
    }
  }
}
