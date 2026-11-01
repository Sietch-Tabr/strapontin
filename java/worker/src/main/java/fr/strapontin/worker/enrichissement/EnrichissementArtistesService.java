/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker.enrichissement;

import fr.strapontin.core.BilanFetch;
import fr.strapontin.sources.SourceAvecBilanFetch;
import fr.strapontin.sources.enrichissement.ArtisteAEnrichir;
import fr.strapontin.sources.enrichissement.EnrichissementArtiste;
import fr.strapontin.sources.enrichissement.SourceAvecAmbiguitesDecouverte;
import fr.strapontin.sources.enrichissement.SourceEnrichissementArtiste;
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
 * Orchestrateur d'enrichissement des artistes (étape 3+),
 * lots de {@link #TAILLE_LOT_PERSISTANCE} (détail : CLAUDE.md,
 * Enrichissement Wikidata/OSM/Deezer).
 * @since 2.6.0
 * @author Kwaite
 */
@Service
public class EnrichissementArtistesService {

  private static final Logger LOG = LoggerFactory.getLogger(EnrichissementArtistesService.class);

  private static final int TAILLE_LOT_PERSISTANCE = 25;
  // Délai entre deux lots de persistance, en plus du délai intra-source
  // (500ms/appel côté WikidataArtisteSource en mode découverte) — laisse
  // Wikidata respirer entre deux lots plutôt que d'enchaîner immédiatement.
  private static final long DELAI_ENTRE_LOTS_MS = 2000;

  private final NamedParameterJdbcTemplate jdbc;
  private final List<SourceEnrichissementArtiste> sources;
  private final LogsAdminWorker logsAdminWorker;
  private final SignalementMatchingService signalementMatchingService;

  /**
   * @param jdbc accès JDBC
   * @param sources sources d'enrichissement injectées, dans leur ordre de
   *     déclaration Spring
   * @param logsAdminWorker journalisation du bilan de cycle
   * @param signalementMatchingService journalisation des ambiguïtés de
   *     découverte
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
   * Enrichit les artistes du jour_batch courant sans {@code wikidata_id}, ou
   * dont les alias Wikidata n'ont jamais été lus.
   * @return nombre d'artistes effectivement mis à jour
   */
  public int enrichir() {
    final List<ArtisteAEnrichir> candidats = selectionnerCandidats();
    if (candidats.isEmpty()) {
      LOG.info("enrichissement:artistes — aucun candidat");
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
    LOG.info("enrichissement:artistes candidats={} enrichis={}", candidats.size(), nbEnrichis);
    remonterBilans();
    return nbEnrichis;
  }

  /**
   * Remonte dans {@code logs_admin} le bilan agrégé d'appels/erreurs de chaque
   * source implémentant {@link SourceAvecBilanFetch} pour le cycle qui vient de
   * se terminer — même mécanisme générique que
   * {@code WorkerStatsService.remonterBilan} et {@link
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
   * Signale chaque ambiguïté de découverte prélevée depuis le dernier cycle —
   * distinct d'une non-découverte ordinaire, pour permettre un traitement
   * différencié.
   */
  private void remonterAmbiguites() {
    for (final SourceEnrichissementArtiste source : sources) {
      if (!(source instanceof SourceAvecAmbiguitesDecouverte avecAmbiguites)) {
        continue;
      }
      for (final UUID artisteId : avecAmbiguites.preleverAmbiguites().keySet()) {
        signalementMatchingService.signaler(
            "wikidata_ambiguite", "artiste", artisteId, "basse", null);
      }
    }
  }

  private int enrichirEtPersisterLot(final List<ArtisteAEnrichir> lot) {
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
      signalerIdentifiantTicketmasterLegacy(artiste, enrichissement);
      nbEnrichis++;
    }
    return nbEnrichis;
  }

  private List<ArtisteAEnrichir> selectionnerCandidats() {
    return jdbc.query(
        """
        SELECT a.id, a.nom, a.nom_norm, a.deezer_id, a.wikidata_id,
               EXISTS (SELECT 1 FROM evenements e WHERE e.artiste_id = a.id
                       AND e.classification IN ('theatre', 'divers')) AS spectacle_non_musical
        FROM artistes a
        LEFT JOIN concerts c ON c.artiste_id = a.id AND c.date >= CURRENT_DATE
        WHERE (a.wikidata_id IS NULL OR a.alias_wikidata IS NULL)
          AND a.jour_batch = EXTRACT(ISODOW FROM CURRENT_DATE)
        GROUP BY a.id, a.nom, a.nom_norm, a.deezer_id, a.wikidata_id
        ORDER BY COUNT(c.id) DESC
        """,
        Map.of(),
        (rs, _) ->
            new ArtisteAEnrichir(
                rs.getObject("id", UUID.class),
                rs.getString("nom"),
                rs.getString("nom_norm"),
                rs.getString("wikidata_id"),
                rs.getString("deezer_id"),
                rs.getBoolean("spectacle_non_musical")));
  }

  private static List<ArtisteAEnrichir> appliquerConnus(
      final List<ArtisteAEnrichir> candidats, final Map<UUID, EnrichissementArtiste> accumule) {
    return candidats.stream()
        .map(
            candidat -> {
              final EnrichissementArtiste enrichissementConnu = accumule.get(candidat.id());
              final String wikidataId =
                  enrichissementConnu != null && enrichissementConnu.wikidataId() != null
                      ? enrichissementConnu.wikidataId()
                      : candidat.wikidataId();
              final String deezerId =
                  enrichissementConnu != null && enrichissementConnu.deezerId() != null
                      ? enrichissementConnu.deezerId()
                      : candidat.deezerId();
              return new ArtisteAEnrichir(
                  candidat.id(),
                  candidat.nom(),
                  candidat.nomNorm(),
                  wikidataId,
                  deezerId,
                  candidat.spectacleNonMusical());
            })
        .toList();
  }

  private static EnrichissementArtiste fusionner(
      final EnrichissementArtiste existant, final EnrichissementArtiste nouveau) {
    return new EnrichissementArtiste(
        nouveau.wikidataId() != null ? nouveau.wikidataId() : existant.wikidataId(),
        nouveau.nomAffiche() != null ? nouveau.nomAffiche() : existant.nomAffiche(),
        nouveau.deezerId() != null ? nouveau.deezerId() : existant.deezerId(),
        nouveau.ticketmasterIdLegacy() != null
            ? nouveau.ticketmasterIdLegacy()
            : existant.ticketmasterIdLegacy(),
        nouveau.aliasWikidata().isEmpty() ? existant.aliasWikidata() : nouveau.aliasWikidata());
  }

  /**
   * @return {@code true} si appliqué, {@code false} si
   * {@code wikidataId} déjà porté par un autre artiste
   * (détail : CLAUDE.md, Enrichissement Wikidata/OSM/Deezer).
   */
  private boolean mettreAJourArtiste(
      final UUID artisteId, final String nom, final EnrichissementArtiste enrichissement) {
    final Map<String, Object> params = new HashMap<>();
    params.put("wikidataId", enrichissement.wikidataId());
    params.put("nomAffiche", enrichissement.nomAffiche());
    params.put("deezerId", enrichissement.deezerId());
    // Liste vide une fois l'élément Wikidata lu : sans alias, l'artiste n'est
    // plus relu ; null tant qu'aucun élément n'est connu.
    params.put(
        "aliasWikidata",
        enrichissement.wikidataId() == null && enrichissement.aliasWikidata().isEmpty()
            ? null
            : enrichissement.aliasWikidata().toArray(String[]::new));
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
              + enrichissement.wikidataId(),
          Map.of("artisteId", artisteId.toString(), "wikidataId", enrichissement.wikidataId()));
      signalementMatchingService.signaler(
          "collision_wikidata_artiste",
          "artiste",
          artisteId,
          "basse",
          Map.of("wikidataId", enrichissement.wikidataId()));
      return false;
    }
  }

  /**
   * P7354 est un identifiant Ticketmaster legacy numérique, sans conversion
   * fiable connue vers l'attractionId Discovery API réellement stocké
   * (format {@code K8vZ...}) — la découverte est journalisée pour une
   * future résolution, jamais persistée ni comparée à l'existant.
   */
  private void signalerIdentifiantTicketmasterLegacy(
      final ArtisteAEnrichir artiste, final EnrichissementArtiste enrichissement) {
    final String tmIdLegacy = enrichissement.ticketmasterIdLegacy();
    if (tmIdLegacy == null) {
      return;
    }
    LOG.info(
        "P7354 — identifiant Ticketmaster legacy découvert artiste={} idLegacy={}",
        artiste.nom(),
        tmIdLegacy);
  }
}
