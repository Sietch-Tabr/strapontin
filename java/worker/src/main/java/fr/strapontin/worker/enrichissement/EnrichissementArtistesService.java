/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker.enrichissement;

import fr.strapontin.core.BilanFetch;
import fr.strapontin.sources.ArtisteAEnrichir;
import fr.strapontin.sources.EnrichissementArtiste;
import fr.strapontin.sources.SourceAvecAmbiguitesDecouverte;
import fr.strapontin.sources.SourceAvecBilanFetch;
import fr.strapontin.sources.SourceEnrichissementArtiste;
import fr.strapontin.worker.LogsAdminWorker;
import fr.strapontin.worker.sante.SignalementMatchingService;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Orchestrateur d'enrichissement des artistes (étape 3 +).
 *
 * <p>Boucle sur les {@link SourceEnrichissementArtiste} injectées (une nouvelle source = un bean
 * Spring, sans modification de ce service). L'ordre de déclaration des beans dans {@code
 * EnrichissementConfiguration} importe : {@code WikidataArtisteSource} doit être déclaré avant
 * {@code DeezerArtisteSource} — le {@code deezerId} (P2722) découvert par Wikidata est propagé aux
 * sources suivantes dans la même passe pour que Deezer puisse confirmer le nom.
 *
 * <p>Contient la logique : le P6262 Wikidata sert de cross-validation indépendante de TM pour
 * l'entrée {@code artiste_sources(source='ticketmaster')} — absent → insertion auto, identique →
 * rien, différent → log seul (pas d'écrasement automatique d'un lien déjà établi).
 *
 * <p>Persistance par lot de {@link #TAILLE_LOT_PERSISTANCE} (et non en un seul bloc final) : la
 * découverte Wikidata sans Q-ID connu est séquentielle (une requête par artiste, rate-limitée) —
 * persister uniquement à la toute fin perdrait tout le travail déjà résolu en cas d'interruption
 * avant la fin du cycle.
 *
 * @since 2.6.0
 * @author Kwaite
 */
@Service
public class EnrichissementArtistesService {

  private static final Logger log = LoggerFactory.getLogger(EnrichissementArtistesService.class);

  static final int TAILLE_LOT_PERSISTANCE = 25;
  // Délai entre deux lots de persistance, en plus du délai intra-source (500ms/appel côté
  // WikidataArtisteSource en mode découverte) — laisse Wikidata respirer entre deux lots plutôt
  // que d'enchaîner immédiatement.
  private static final long DELAI_ENTRE_LOTS_MS = 2000;

  private final NamedParameterJdbcTemplate jdbc;
  private final List<SourceEnrichissementArtiste> sources;
  private final LogsAdminWorker logsAdminWorker;
  private final SignalementMatchingService signalementMatchingService;

  /**
   * @param jdbc accès JDBC
   * @param sources sources d'enrichissement injectées, dans leur ordre de déclaration Spring
   * @param logsAdminWorker journalisation du bilan de cycle
   * @param signalementMatchingService journalisation des ambiguïtés de découverte
   */
  public EnrichissementArtistesService(
      final NamedParameterJdbcTemplate jdbc,
      final List<SourceEnrichissementArtiste> sources,
      final LogsAdminWorker logsAdminWorker,
      final SignalementMatchingService signalementMatchingService) {
    this.jdbc = jdbc;
    this.sources = sources;
    this.logsAdminWorker = logsAdminWorker;
    this.signalementMatchingService = signalementMatchingService;
  }

  /**
   * Enrichit les artistes sans {@code wikidata_id} dont {@code jour_batch} correspond au jour ISO
   * courant (rotation hebdomadaire, — remplace l'ancien plafond fixe de 500/nuit), priorisés par
   * nombre de concerts liés à l'intérieur de ce sous-ensemble. N'écrase jamais un {@code
   * nom_affiche} déjà présent (override manuel admin).
   *
   * @return nombre d'artistes effectivement mis à jour
   */
  public int enrichir() {
    final List<ArtisteAEnrichir> candidats = selectionnerCandidats();
    if (candidats.isEmpty()) {
      log.info("enrichissement:artistes — aucun candidat");
      return 0;
    }

    int nbEnrichis = 0;
    for (int i = 0; i < candidats.size(); i += TAILLE_LOT_PERSISTANCE) {
      if (i > 0) {
        EnrichissementUtils.attendreEntreLots(DELAI_ENTRE_LOTS_MS);
      }
      final List<ArtisteAEnrichir> lot =
          candidats.subList(i, Math.min(i + TAILLE_LOT_PERSISTANCE, candidats.size()));
      nbEnrichis += enrichirEtPersisterLot(lot);
    }
    log.info("enrichissement:artistes candidats={} enrichis={}", candidats.size(), nbEnrichis);
    remonterBilans();
    return nbEnrichis;
  }

  /**
   * Remonte dans {@code logs_admin} le bilan agrégé d'appels/erreurs de chaque source implémentant
   * {@link SourceAvecBilanFetch} pour le cycle qui vient de se terminer — même mécanisme générique
   * que {@code WorkerStatsService.remonterBilan} et {@link
   * EnrichissementSallesService#remonterBilans}.
   */
  private void remonterBilans() {
    for (final SourceEnrichissementArtiste source : sources) {
      if (!(source instanceof SourceAvecBilanFetch avecBilan)) {
        continue;
      }
      final BilanFetch bilan = avecBilan.preleverBilan();
      bilan
          .evaluer(
              "reconciliation:nocturne",
              EnrichissementUtils.capitaliser(source.nom()),
              avecBilan.seuilAppelsMinAlerte())
          .ifPresent(
              entree ->
                  logsAdminWorker.inserer(
                      entree.niveau(), "worker", entree.message(), entree.meta()));
    }
    remonterAmbiguites();
  }

  /**
   * Signale chaque ambiguïté de découverte prélevée depuis le dernier cycle — distinct d'une
   * non-découverte ordinaire, pour permettre un traitement différencié.
   */
  private void remonterAmbiguites() {
    for (final SourceEnrichissementArtiste source : sources) {
      if (!(source instanceof SourceAvecAmbiguitesDecouverte avecAmbiguites)) {
        continue;
      }
      for (final UUID artisteId : avecAmbiguites.preleverAmbiguites()) {
        signalementMatchingService.signaler(
            "wikidata_ambiguite", "artiste", artisteId, "basse", null);
      }
    }
  }

  private int enrichirEtPersisterLot(final List<ArtisteAEnrichir> lot) {
    final Map<UUID, String> attractionIdsExistants = lireAttractionIdsExistants(lot);

    final Map<UUID, EnrichissementArtiste> accumule = new HashMap<>();
    List<ArtisteAEnrichir> courant = lot;
    for (final SourceEnrichissementArtiste source : sources) {
      final Map<UUID, EnrichissementArtiste> resultat = source.enrichirLot(courant);
      for (final Map.Entry<UUID, EnrichissementArtiste> entree : resultat.entrySet()) {
        accumule.merge(
            entree.getKey(), entree.getValue(), EnrichissementArtistesService::fusionner);
      }
      courant = appliquerConnus(courant, accumule);
    }

    int nbEnrichis = 0;
    for (final ArtisteAEnrichir artiste : lot) {
      final EnrichissementArtiste enrichissement = accumule.get(artiste.id());
      if (enrichissement == null) {
        continue;
      }
      if (!mettreAJourArtiste(artiste.id(), artiste.nom(), enrichissement)) {
        continue;
      }
      traiterCrossValidationP6262(
          artiste, enrichissement, attractionIdsExistants.get(artiste.id()));
      nbEnrichis++;
    }
    return nbEnrichis;
  }

  private List<ArtisteAEnrichir> selectionnerCandidats() {
    return jdbc.query(
        """
        SELECT a.id, a.nom, a.nom_norm, a.deezer_id
        FROM artistes a
        LEFT JOIN concerts c ON c.artiste_id = a.id AND c.date >= CURRENT_DATE
        WHERE a.wikidata_id IS NULL AND a.jour_batch = EXTRACT(ISODOW FROM CURRENT_DATE)
        GROUP BY a.id, a.nom, a.nom_norm, a.deezer_id
        ORDER BY COUNT(c.id) DESC
        """,
        Map.of(),
        (rs, _) ->
            new ArtisteAEnrichir(
                rs.getObject("id", UUID.class),
                rs.getString("nom"),
                rs.getString("nom_norm"),
                null,
                rs.getString("deezer_id")));
  }

  private Map<UUID, String> lireAttractionIdsExistants(final List<ArtisteAEnrichir> candidats) {
    final List<UUID> ids = candidats.stream().map(ArtisteAEnrichir::id).toList();
    final List<Map<String, Object>> lignes =
        jdbc.queryForList(
            """
            SELECT artiste_id, identifiant_externe
            FROM artiste_sources
            WHERE source = 'ticketmaster' AND artiste_id IN (:ids)
            """,
            Map.of("ids", ids));
    final Map<UUID, String> resultat = new HashMap<>();
    for (final Map<String, Object> ligne : lignes) {
      resultat.put((UUID) ligne.get("artiste_id"), (String) ligne.get("identifiant_externe"));
    }
    return resultat;
  }

  private static List<ArtisteAEnrichir> appliquerConnus(
      final List<ArtisteAEnrichir> candidats, final Map<UUID, EnrichissementArtiste> accumule) {
    return candidats.stream()
        .map(
            a -> {
              final EnrichissementArtiste e = accumule.get(a.id());
              final String wikidataId =
                  e != null && e.wikidataId() != null ? e.wikidataId() : a.wikidataId();
              final String deezerId =
                  e != null && e.deezerId() != null ? e.deezerId() : a.deezerId();
              return new ArtisteAEnrichir(a.id(), a.nom(), a.nomNorm(), wikidataId, deezerId);
            })
        .toList();
  }

  private static EnrichissementArtiste fusionner(
      final EnrichissementArtiste a, final EnrichissementArtiste b) {
    return new EnrichissementArtiste(
        b.wikidataId() != null ? b.wikidataId() : a.wikidataId(),
        b.nomAffiche() != null ? b.nomAffiche() : a.nomAffiche(),
        b.deezerId() != null ? b.deezerId() : a.deezerId(),
        b.ticketmasterAttractionId() != null
            ? b.ticketmasterAttractionId()
            : a.ticketmasterAttractionId(),
        b.aliasWikidata().isEmpty() ? a.aliasWikidata() : b.aliasWikidata());
  }

  /**
   * @return {@code true} si la mise à jour a été appliquée, {@code false} si le {@code wikidataId}
   *     découvert est déjà porté par un autre artiste (contrainte {@code idx_artistes_wikidata_id})
   *     — signe d'un faux positif de correspondance ou d'un doublon non encore fusionné; le conflit
   *     est loggé et signalé ({@link SignalementMatchingService}) plutôt que de faire échouer tout
   *     le cycle de réconciliation, et la cross-validation P6262 associée est également sautée
   *     (basée sur le même enrichissement). Jamais de fusion automatique — contrairement aux
   *     salles, aucun signal géographique de cross-validation n'existe pour départager un homonyme
   *     réel d'un doublon.
   */
  private boolean mettreAJourArtiste(
      final UUID artisteId, final String nom, final EnrichissementArtiste e) {
    final Map<String, Object> params = new HashMap<>();
    params.put("wikidataId", e.wikidataId());
    params.put("nomAffiche", e.nomAffiche());
    params.put("deezerId", e.deezerId());
    params.put(
        "aliasWikidata",
        e.aliasWikidata().isEmpty() ? null : e.aliasWikidata().toArray(String[]::new));
    params.put("id", artisteId.toString());
    try {
      jdbc.update(
          """
          UPDATE artistes SET
            wikidata_id    = COALESCE(wikidata_id, :wikidataId),
            nom_affiche    = COALESCE(nom_affiche, :nomAffiche),
            deezer_id      = COALESCE(deezer_id, :deezerId),
            alias_wikidata = COALESCE(alias_wikidata, :aliasWikidata)
          WHERE id = CAST(:id AS uuid)
          """,
          params);
      return true;
    } catch (DuplicateKeyException ex) {
      logsAdminWorker.inserer(
          "warn",
          "enrichissement-wikidata",
          "wikidataId déjà attribué à un autre artiste — conflit ignoré artiste="
              + nom
              + " wikidataId="
              + e.wikidataId(),
          Map.of("artisteId", artisteId.toString(), "wikidataId", e.wikidataId()));
      signalementMatchingService.signaler(
          "collision_wikidata_artiste",
          "artiste",
          artisteId,
          "basse",
          Map.of("wikidataId", e.wikidataId()));
      return false;
    }
  }

  /** P6262 fait autorité pour le lien TM, indépendamment de la découverte par mot-clé. */
  private void traiterCrossValidationP6262(
      final ArtisteAEnrichir artiste,
      final EnrichissementArtiste enrichissement,
      final String attractionIdExistant) {
    final String tmIdWikidata = enrichissement.ticketmasterAttractionId();
    if (tmIdWikidata == null) {
      return;
    }

    if (attractionIdExistant == null) {
      final int lignesInserees =
          jdbc.update(
              """
              INSERT INTO artiste_sources (artiste_id, source, identifiant_externe)
              VALUES (CAST(:artisteId AS uuid), 'ticketmaster', :tmId)
              ON CONFLICT DO NOTHING
              """,
              Map.of("artisteId", artiste.id().toString(), "tmId", tmIdWikidata));
      if (lignesInserees > 0) {
        log.info("P6262 — lien TM inséré artiste={} attractionId={}", artiste.nom(), tmIdWikidata);
      } else {
        // Conflit réel sur la PK (source, identifiant_externe) — cet identifiant TM est déjà
        // attribué à un autre artiste, contrairement à la collision wikidata_id, ce cas n'avait
        // jusqu'ici aucune trace (ni log, ni signalement).
        signalementMatchingService.signaler(
            "reattribution_tm_artiste",
            "artiste",
            artiste.id(),
            "basse",
            Map.of("attractionIdTicketmaster", tmIdWikidata));
      }
    } else if (!attractionIdExistant.equals(tmIdWikidata)) {
      logsAdminWorker.inserer(
          "warn",
          "enrichissement-wikidata",
          "discordance P6262 artiste="
              + artiste.nom()
              + " tmActuel="
              + attractionIdExistant
              + " tmWikidata="
              + tmIdWikidata,
          Map.of(
              "artisteId", artiste.id().toString(),
              "attractionIdActuel", attractionIdExistant,
              "attractionIdWikidata", tmIdWikidata));
    }
  }
}
