/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker.sante;

import fr.strapontin.core.CorrespondanceArtiste;
import fr.strapontin.core.Normalisation;
import fr.strapontin.sources.TicketmasterSource;
import fr.strapontin.sources.TicketmasterSource.StatutIdTM;
import fr.strapontin.worker.LogsAdminWorker;
import java.sql.Array;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Health-check et redécouverte active des identifiants Ticketmaster suspects, et vérification de
 * cohérence nom/artiste — extrait de {@link ReconciliationNocturneService} pour rester sous le
 * seuil de couplage entre objets (20).
 *
 * <p>Bean Spring (pas seulement assemblé manuellement) pour que {@link
 * ReconciliationNocturneService} le reçoive comme une seule dépendance plutôt que de reconstruire
 * jdbc/tm/logsAdminWorker à chaque fois — voir le seuil de nombre de paramètres de constructeur
 * (8).
 *
 * @since 2.9.1
 * @author Kwaite
 */
@Service
public final class HealthCheckArtistesTM {

  private static final Logger LOG = LoggerFactory.getLogger(HealthCheckArtistesTM.class);
  private static final String CLE_ARTISTE_ID = "artisteId";
  private static final String NIVEAU_WARN = "warn";
  private static final String SOURCE_RECONCILIATION_NOCTURNE = "reconciliation-nocturne";

  private final NamedParameterJdbcTemplate jdbc;
  private final TicketmasterSource tm;
  private final LogsAdminWorker logsAdminWorker;

  /**
   * @param jdbc accès JDBC
   * @param tm health-check et redécouverte des identifiants TM
   * @param logsAdminWorker journalisation des incohérences détectées
   */
  public HealthCheckArtistesTM(
      final NamedParameterJdbcTemplate jdbc,
      final TicketmasterSource tm,
      final LogsAdminWorker logsAdminWorker) {
    this.jdbc = jdbc;
    this.tm = tm;
    this.logsAdminWorker = logsAdminWorker;
  }

  /**
   * Vérifie que le nom retourné par TM pour chaque attractionId lié correspond toujours à l'artiste
   * suivi — contrôle distinct du health-check de vivacité ({@link #verifierVivacite}), qui ne
   * détecte qu'un identifiant mort (HTTP 404), jamais un identifiant vivant mais rattaché à la
   * mauvaise entité (tribute, homonyme). S'applique à <strong>tous</strong> les artistes liés TM,
   * pas seulement les {@code suspect_depuis} : un tribute/homonyme a des événements à venir, il ne
   * déclenche donc jamais le compteur passif {@code nb_zero_consecutifs}.
   *
   * <p>La comparaison passe par {@link CorrespondanceArtiste#estDoublonArtiste} (même algorithme
   * flou que la réconciliation) plutôt qu'une égalité stricte post-normalisation — un écart
   * purement cosmétique (espace, article) n'est jamais signalé. Un écart réel (nom de scène) n'est
   * resignalé que si le nom TM diffère de {@code nom_tm_ignore}, la dernière valeur explicitement
   * acceptée par un admin.
   *
   * @return nombre d'incohérences détectées
   */
  int verifierNoms() {
    final List<Map<String, Object>> lies =
        jdbc.queryForList(
            """
            SELECT asrc.artiste_id, asrc.identifiant_externe, asrc.necessite_intervention_le,
                   asrc.nom_tm_ignore, a.nom
            FROM artistes a JOIN artiste_sources asrc ON asrc.artiste_id = a.id
            WHERE asrc.source = 'ticketmaster'
            """,
            Map.of());

    int incoherences = 0;
    for (final Map<String, Object> ligne : lies) {
      final UUID artisteId = (UUID) ligne.get("artiste_id");
      final String attractionId = (String) ligne.get("identifiant_externe");
      final String nom = (String) ligne.get("nom");
      final String nomTmIgnore = (String) ligne.get("nom_tm_ignore");
      final boolean dejaSignale = ligne.get("necessite_intervention_le") != null;
      final String nomAttraction = tm.recupererNomAttraction(attractionId);
      if (nomAttraction == null) {
        continue;
      }
      if (CorrespondanceArtiste.estDoublonArtiste(
          Normalisation.normaliserTexte(nom), Normalisation.normaliserTexte(nomAttraction))) {
        continue;
      }
      if (nomAttraction.equals(nomTmIgnore)) {
        continue;
      }
      if (preAccepterSiAliasWikidataConnu(artisteId, nomAttraction)) {
        continue;
      }
      incoherences++;
      signalerNomIncoherent(artisteId, attractionId, nom, nomAttraction, dejaSignale);
    }
    return incoherences;
  }

  /**
   * Pré-accepte automatiquement un nom TM incohérent qui correspond en réalité à un alias Wikidata
   * déjà connu de l'artiste (nom de scène alternatif, ex. « -M- » pour Matthieu Chédid) — même
   * effet que {@code ReferentielArtistesService.ignorerIntervention}, sans attendre une première
   * validation manuelle admin puisque Wikidata fait déjà autorité sur cet alias.
   *
   * @return {@code true} si le nom a été pré-accepté (aucun signalement à faire)
   */
  private boolean preAccepterSiAliasWikidataConnu(
      final UUID artisteId, final String nomAttraction) {
    final List<String> alias = lireAliasWikidata(artisteId);
    if (alias.isEmpty()) {
      return false;
    }
    final String nomAttractionNorm = Normalisation.normaliserTexte(nomAttraction);
    final boolean correspond =
        alias.stream()
            .anyMatch(
                a ->
                    CorrespondanceArtiste.estDoublonArtiste(
                        Normalisation.normaliserTexte(a), nomAttractionNorm));
    if (!correspond) {
      return false;
    }
    jdbc.update(
        """
        UPDATE artiste_sources
        SET necessite_intervention_le = NULL, nom_tm_ignore = :nomAttraction,
            nom_tm_incoherent = :nomAttraction
        WHERE artiste_id = CAST(:artisteId AS uuid) AND source = 'ticketmaster'
        """,
        Map.of(CLE_ARTISTE_ID, artisteId.toString(), "nomAttraction", nomAttraction));
    return true;
  }

  private List<String> lireAliasWikidata(final UUID artisteId) {
    final List<String[]> resultats =
        jdbc.query(
            "SELECT alias_wikidata FROM artistes WHERE id = CAST(:id AS uuid)",
            Map.of("id", artisteId.toString()),
            (rs, _) -> {
              final Array tableau = rs.getArray("alias_wikidata");
              return tableau != null ? (String[]) tableau.getArray() : new String[0];
            });
    return resultats.isEmpty() ? List.of() : Arrays.asList(resultats.get(0));
  }

  /**
   * Marque l'artiste comme nécessitant une intervention et journalise l'incohérence.
   *
   * <p>Le WARN n'est journalisé qu'à la première détection ({@code dejaSignale} faux) — un état
   * déjà connu et stable (le flag reste posé tant qu'aucun fetch positif cohérent ou résolution
   * manuelle ne l'efface) ne doit pas générer indéfiniment le même WARN à chaque cycle nocturne.
   * {@code nom_tm_incoherent} est en revanche toujours rafraîchi, y compris quand le flag était
   * déjà posé — {@code ignorerIntervention} (module web) doit pouvoir mémoriser le nom TM *courant*
   * comme accepté, pas une valeur obsolète d'une nuit précédente.
   */
  private void signalerNomIncoherent(
      final UUID artisteId,
      final String attractionId,
      final String nom,
      final String nomAttraction,
      final boolean dejaSignale) {
    jdbc.update(
        """
        UPDATE artiste_sources
        SET necessite_intervention_le = COALESCE(necessite_intervention_le, now()),
            nom_tm_incoherent = :nomAttraction
        WHERE artiste_id = CAST(:artisteId AS uuid) AND source = 'ticketmaster'
        """,
        Map.of(CLE_ARTISTE_ID, artisteId.toString(), "nomAttraction", nomAttraction));
    if (dejaSignale) {
      return;
    }
    logsAdminWorker.inserer(
        NIVEAU_WARN,
        SOURCE_RECONCILIATION_NOCTURNE,
        "nom TM incohérent avec l'artiste suivi artiste="
            + nom
            + " nomTM="
            + nomAttraction
            + " attractionId="
            + attractionId,
        Map.of(
            CLE_ARTISTE_ID,
            artisteId.toString(),
            "attractionId",
            attractionId,
            "nomTM",
            nomAttraction));
  }

  void verifierVivacite() {
    final List<Map<String, Object>> suspects =
        jdbc.queryForList(
            """
            SELECT asrc.artiste_id, asrc.identifiant_externe, a.nom
            FROM artiste_sources asrc JOIN artistes a ON a.id = asrc.artiste_id
            WHERE asrc.source = 'ticketmaster' AND asrc.suspect_depuis IS NOT NULL
            """,
            Map.of());

    for (final Map<String, Object> ligne : suspects) {
      final UUID artisteId = (UUID) ligne.get("artiste_id");
      final String ancienAttractionId = (String) ligne.get("identifiant_externe");
      final String nom = (String) ligne.get("nom");
      if (tm.verifierAttraction(ancienAttractionId) != StatutIdTM.MORT) {
        reinitialiserSuspect(artisteId);
        continue;
      }
      redecouvrirArtiste(artisteId, ancienAttractionId, nom);
    }
  }

  /**
   * Le compteur passif ({@code nb_zero_consecutifs}) peut signaler à tort un identifiant suspect
   * alors qu'il s'agit simplement d'un artiste sans événement à venir pour l'instant — la
   * vérification active de cette phase fait autorité et doit effacer ce faux positif dès qu'elle
   * confirme que l'identifiant TM est toujours vivant, plutôt que de laisser le statut "suspect"
   * perdurer jusqu'à expiration (24h).
   */
  private void reinitialiserSuspect(final UUID artisteId) {
    jdbc.update(
        "UPDATE artiste_sources SET nb_zero_consecutifs = 0, suspect_depuis = NULL"
            + " WHERE artiste_id = CAST(:id AS uuid) AND source = 'ticketmaster'",
        Map.of("id", artisteId.toString()));
  }

  private void redecouvrirArtiste(
      final UUID artisteId, final String ancienAttractionId, final String nom) {
    final String nouveauId = tm.rechercherAttractionId(nom);
    if (nouveauId != null
        && !nouveauId.equals(ancienAttractionId)
        && !dejaUtiliseParAutreEntite(artisteId, nouveauId)) {
      jdbc.update(
          """
          UPDATE artiste_sources
          SET identifiant_externe = :nouveauId, nb_zero_consecutifs = 0, suspect_depuis = NULL
          WHERE artiste_id = CAST(:artisteId AS uuid) AND source = 'ticketmaster'
          """,
          Map.of("nouveauId", nouveauId, CLE_ARTISTE_ID, artisteId.toString()));
      LOG.info(
          "reconciliation:nocturne — artiste redécouvert nom={} ancien={} nouveau={}",
          nom,
          ancienAttractionId,
          nouveauId);
      return;
    }
    jdbc.update(
        """
        UPDATE artiste_sources SET necessite_intervention_le = now()
        WHERE artiste_id = CAST(:artisteId AS uuid) AND source = 'ticketmaster'
        """,
        Map.of(CLE_ARTISTE_ID, artisteId.toString()));
    logsAdminWorker.inserer(
        NIVEAU_WARN,
        SOURCE_RECONCILIATION_NOCTURNE,
        "redécouverte artiste échouée nom=" + nom + " ancienAttractionId=" + ancienAttractionId,
        Map.of(CLE_ARTISTE_ID, artisteId.toString(), "ancienAttractionId", ancienAttractionId));
  }

  private boolean dejaUtiliseParAutreEntite(final UUID idActuel, final String candidatId) {
    final List<Map<String, Object>> lignes =
        jdbc.queryForList(
            "SELECT 1 FROM artiste_sources WHERE source = 'ticketmaster' AND identifiant_externe ="
                + " :candidatId AND artiste_id != CAST(:idActuel AS uuid)",
            Map.of("candidatId", candidatId, "idActuel", idActuel.toString()));
    return !lignes.isEmpty();
  }
}
