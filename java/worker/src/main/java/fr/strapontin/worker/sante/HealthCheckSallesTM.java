/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker.sante;

import fr.strapontin.core.PositionLieu;
import fr.strapontin.sources.ticketmaster.TicketmasterSource;
import fr.strapontin.sources.ticketmaster.TicketmasterSource.CandidatVenueTM;
import fr.strapontin.sources.ticketmaster.TicketmasterSource.StatutIdTM;
import fr.strapontin.worker.LogsAdminWorker;
import java.sql.Types;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Health-check et redécouverte active des identifiants TM suspects
 * pour les salles, et signalement des lieux Eventim suspects —
 * extrait de {@link ReconciliationNocturneService} (voir {@link
 * HealthCheckArtistesTM}, variante artiste).
 * @since 2.9.1
 * @author Kwaite
 */
@Service
public final class HealthCheckSallesTM {

  private static final Logger LOG = LoggerFactory.getLogger(HealthCheckSallesTM.class);
  private static final String NIVEAU_WARN = "warn";
  private static final String SOURCE_RECONCILIATION_NOCTURNE = "reconciliation-nocturne";
  private static final int NB_CANDIDATS_NON_AMBIGU = 1;
  /* package-private : visibilité élargie pour les tests (même package). */
  static final int LIMITE_POSITIONS_PAR_NUIT = 50;

  private final NamedParameterJdbcTemplate jdbc;
  private final TicketmasterSource tm;
  private final LogsAdminWorker logsAdminWorker;

  /**
   * @param jdbc accès JDBC
   * @param tm health-check et redécouverte des identifiants TM
   * @param logsAdminWorker journalisation des ambiguïtés/suspicions détectées
   */
  public HealthCheckSallesTM(
      final NamedParameterJdbcTemplate jdbc,
      final TicketmasterSource tm,
      final LogsAdminWorker logsAdminWorker) {
    this.jdbc = jdbc;
    this.tm = tm;
    this.logsAdminWorker = logsAdminWorker;
  }

  /**
   * @return tableau {@code [verifiees, redecouvertes, ambigues]}
   */
  /* package-private : appelé par ReconciliationNocturneService (même
   * package). */
  int[] verifier() {
    final List<Map<String, Object>> suspectes =
        jdbc.queryForList(
            """
            SELECT ss.salle_id, ss.identifiant_externe, s.nom
            FROM salle_sources ss JOIN salles s ON s.id = ss.salle_id
            WHERE ss.source = 'ticketmaster' AND ss.suspect_depuis IS NOT NULL
            """,
            Map.of());

    int verifiees = 0;
    int redecouvertes = 0;
    int ambigues = 0;
    for (final Map<String, Object> ligne : suspectes) {
      if (quotaEpuise("vérification des salles")) {
        break;
      }
      final UUID salleId = (UUID) ligne.get("salle_id");
      final String ancienVenueId = (String) ligne.get("identifiant_externe");
      final String nom = (String) ligne.get("nom");
      verifiees++;
      final StatutIdTM statut = tm.verifierVenue(ancienVenueId);
      // Indéterminé (appel en échec) : rien n'est conclu, la salle reste suspecte.
      if (statut != StatutIdTM.MORT) {
        if (statut == StatutIdTM.VIVANT) {
          reinitialiserSuspect(salleId);
        }
        continue;
      }

      final int resultatRedecouverte = redecouvrirSalle(salleId, ancienVenueId, nom);
      if (resultatRedecouverte > 0) {
        redecouvertes++;
      } else if (resultatRedecouverte < 0) {
        ambigues++;
      }
    }
    return new int[] {verifiees, redecouvertes, ambigues};
  }

  /**
   * @return 1 si redécouverte automatique effectuée, -1 si ambigu (0 ou ≥2
   *     candidats), 0 sinon
   */
  private int redecouvrirSalle(final UUID salleId, final String ancienVenueId, final String nom) {
    final List<CandidatVenueTM> candidats = tm.rechercherVenuesParNom(nom);
    final List<CandidatVenueTM> valides =
        candidats.stream()
            .filter(c -> !c.id().equals(ancienVenueId))
            .filter(c -> tm.compterEventsVenue(c.id()) > 0)
            .filter(c -> !dejaUtiliseParAutreEntite(salleId, c.id()))
            .toList();

    if (valides.size() == NB_CANDIDATS_NON_AMBIGU) {
      final String nouveauId = valides.get(0).id();
      jdbc.update(
          """
          UPDATE salle_sources
          SET identifiant_externe = :nouveauId, nb_zero_consecutifs = 0, suspect_depuis = NULL
          WHERE salle_id = CAST(:salleId AS uuid) AND source = 'ticketmaster'
          """,
          Map.of("nouveauId", nouveauId, "salleId", salleId.toString()));
      LOG.info(
          "reconciliation:nocturne — salle redécouverte nom={} ancien={} nouveau={}",
          nom,
          ancienVenueId,
          nouveauId);
      return 1;
    }

    logsAdminWorker.inserer(
        NIVEAU_WARN,
        SOURCE_RECONCILIATION_NOCTURNE,
        "redécouverte ambiguë salle="
            + nom
            + " ancienVenueId="
            + ancienVenueId
            + " candidats="
            + valides.size(),
        Map.of(
            "salleId",
            salleId.toString(),
            "ancienVenueId",
            ancienVenueId,
            "nbCandidats",
            valides.size()));
    return -1;
  }

  /**
   * Le compteur passif ({@code nb_zero_consecutifs}) peut signaler à tort une
   * salle suspecte alors qu'il s'agit simplement d'une salle sans événement à
   * venir pour l'instant — la vérification active de cette phase fait autorité
   * et doit effacer ce faux positif dès qu'elle confirme que l'identifiant TM
   * est toujours vivant, plutôt que de laisser le statut "suspect" perdurer
   * jusqu'à expiration (24h, cf. {@link #verifier()} ).
   */
  private void reinitialiserSuspect(final UUID salleId) {
    jdbc.update(
        "UPDATE salle_sources SET nb_zero_consecutifs = 0, suspect_depuis = NULL"
            + " WHERE salle_id = CAST(:id AS uuid) AND source = 'ticketmaster'",
        Map.of("id", salleId.toString()));
  }

  private boolean dejaUtiliseParAutreEntite(final UUID idActuel, final String candidatId) {
    final List<Map<String, Object>> lignes =
        jdbc.queryForList(
            "SELECT 1 FROM salle_sources WHERE source = 'ticketmaster' AND identifiant_externe ="
                + " :candidatId AND salle_id != CAST(:idActuel AS uuid)",
            Map.of("candidatId", candidatId, "idActuel", idActuel.toString()));
    return !lignes.isEmpty();
  }

  /**
   * Signale les lieux Eventim suspects — pas de redécouverte automatique
   * (Eventim n'expose aucun identifiant de lieu, libellé corrigé à la main).
   */
  /* package-private : appelé par ReconciliationNocturneService (même
   * package). */
  void alerterLieuxEventimSuspects() {
    final List<Map<String, Object>> suspectes =
        jdbc.queryForList(
            """
            SELECT ss.libelle_source, s.nom
            FROM salle_sources ss JOIN salles s ON s.id = ss.salle_id
            WHERE ss.source = 'eventim' AND ss.libelle_source IS NOT NULL
              AND ss.suspect_depuis IS NOT NULL
            """,
            Map.of());
    if (suspectes.isEmpty()) {
      return;
    }

    logsAdminWorker.inserer(
        NIVEAU_WARN,
        SOURCE_RECONCILIATION_NOCTURNE,
        suspectes.size()
            + " lieu(x) Eventim suspect(s) — correction manuelle via l'onglet admin Sources",
        Map.of("nbSuspectes", suspectes.size()));
  }

  /**
   * Rattrape la position des lieux Ticketmaster qui n'en ont pas : un lieu
   * programmé la reçoit à l'ingestion de ses concerts, un lieu sans concert
   * en cours jamais. Borné à {@value #LIMITE_POSITIONS_PAR_NUIT} lieux par
   * nuit pour préserver le quota Ticketmaster.
   * @return nombre de lieux dont la position a été obtenue
   */
  /* package-private : appelé par ReconciliationNocturneService (même package). */
  int rattraperPositions() {
    final List<Map<String, Object>> lieux =
        jdbc.queryForList(
            """
            SELECT salle_id::text AS salle_id, identifiant_externe
            FROM salle_sources
            WHERE source = 'ticketmaster' AND latitude IS NULL
            ORDER BY identifiant_externe
            LIMIT :limite
            """,
            Map.of("limite", LIMITE_POSITIONS_PAR_NUIT));
    int obtenues = 0;
    for (final Map<String, Object> lieu : lieux) {
      if (quotaEpuise("lecture des positions")) {
        break;
      }
      final PositionLieu position = tm.lirePositionVenue((String) lieu.get("identifiant_externe"));
      if (!position.estGeolocalisee()) {
        continue;
      }
      final MapSqlParameterSource params =
          new MapSqlParameterSource()
              .addValue("identifiant", lieu.get("identifiant_externe"))
              .addValue("salleId", lieu.get("salle_id"))
              .addValue("latitude", position.latitude(), Types.DOUBLE)
              .addValue("longitude", position.longitude(), Types.DOUBLE)
              .addValue("codePostal", position.codePostal(), Types.VARCHAR);
      jdbc.update(
          """
          UPDATE salle_sources
          SET latitude = :latitude, longitude = :longitude,
              code_postal = COALESCE(:codePostal, code_postal)
          WHERE source = 'ticketmaster' AND identifiant_externe = :identifiant
          """,
          params);
      jdbc.update(
          """
          UPDATE salles
          SET latitude = :latitude, longitude = :longitude, origine_position = 'sources',
              code_postal = COALESCE(code_postal, :codePostal)
          WHERE id = CAST(:salleId AS uuid)
            AND (origine_position IS NULL OR origine_position = 'geocodage')
          """,
          params);
      obtenues++;
    }
    return obtenues;
  }

  // Quota du jour épuisé : poursuivre ne ferait qu'accumuler des refus.
  private boolean quotaEpuise(final String etape) {
    if (tm.quotaEpuise()) {
      LOG.info("reconciliation:nocturne — {} interrompue : quota Ticketmaster épuisé", etape);
      return true;
    }
    return false;
  }
}
