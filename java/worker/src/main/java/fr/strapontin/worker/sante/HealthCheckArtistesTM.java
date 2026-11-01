/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker.sante;

import fr.strapontin.core.CorrespondanceArtiste;
import fr.strapontin.core.Normalisation;
import fr.strapontin.sources.ticketmaster.TicketmasterSource;
import fr.strapontin.sources.ticketmaster.TicketmasterSource.StatutIdTM;
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
 * Health-check et redécouverte active des identifiants Ticketmaster
 * suspects, et vérification de cohérence nom/artiste — extrait de
 * {@link ReconciliationNocturneService}.
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
   * Vérifie que le nom retourné par TM pour chaque attractionId lié
   * correspond toujours à l'artiste suivi (détail : CLAUDE.md,
   * Santé/référentiel admin) — distinct du health-check de vivacité
   * ({@link #verifierVivacite}, HTTP 404 seulement).
   * @return nombre d'incohérences détectées
   */
  /* package-private : appelé par ReconciliationNocturneService (même
   * package). */
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
      if (quotaEpuise("vérification des noms")) {
        break;
      }
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
   * Pré-accepte automatiquement un nom TM incohérent correspondant à
   * un alias Wikidata déjà connu (détail : CLAUDE.md, Santé/
   * référentiel admin).
   * @return {@code true} si le nom a été pré-accepté
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
   * Marque l'artiste comme nécessitant une intervention et journalise —
   * WARN seulement à la première détection ({@code dejaSignale} faux),
   * mais {@code nom_tm_incoherent} toujours rafraîchi (pour que {@code
   * ignorerIntervention} mémorise le nom TM courant, pas une valeur
   * obsolète).
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

  /* package-private : appelé par ReconciliationNocturneService (même
   * package). */
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
      if (quotaEpuise("vérification de vivacité")) {
        break;
      }
      final UUID artisteId = (UUID) ligne.get("artiste_id");
      final String ancienAttractionId = (String) ligne.get("identifiant_externe");
      final StatutIdTM statut = tm.verifierAttraction(ancienAttractionId);
      // Indéterminé (appel en échec) : rien n'est conclu, l'artiste reste suspect.
      if (statut == StatutIdTM.VIVANT) {
        reinitialiserSuspect(artisteId);
      } else if (statut == StatutIdTM.MORT) {
        redecouvrirArtiste(artisteId, ancienAttractionId, (String) ligne.get("nom"));
      }
    }
  }

  // Quota du jour épuisé : poursuivre ne ferait qu'accumuler des refus.
  private boolean quotaEpuise(final String etape) {
    if (tm.quotaEpuise()) {
      LOG.info("reconciliation:nocturne — {} interrompue : quota Ticketmaster épuisé", etape);
      return true;
    }
    return false;
  }

  /**
   * Identifiant confirmé vivant : le compteur passif signalait à tort un
   * artiste simplement sans date annoncée. La date de vérification évite de
   * le revérifier chaque nuit (voir
   * {@link SuiviSanteSourcesService#DELAI_REVERIFICATION_VIVACITE_JOURS}).
   */
  private void reinitialiserSuspect(final UUID artisteId) {
    jdbc.update(
        "UPDATE artiste_sources SET nb_zero_consecutifs = 0, suspect_depuis = NULL,"
            + " vivacite_verifiee_le = now()"
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
