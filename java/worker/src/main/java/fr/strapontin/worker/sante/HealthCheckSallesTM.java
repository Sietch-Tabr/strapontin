/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker.sante;

import fr.strapontin.sources.TicketmasterSource;
import fr.strapontin.sources.TicketmasterSource.CandidatVenueTM;
import fr.strapontin.sources.TicketmasterSource.StatutIdTM;
import fr.strapontin.worker.LogsAdminWorker;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Health-check et redécouverte active des identifiants TM suspects
 * pour les salles, et signalement des slugs Digitick suspects —
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
      final UUID salleId = (UUID) ligne.get("salle_id");
      final String ancienVenueId = (String) ligne.get("identifiant_externe");
      final String nom = (String) ligne.get("nom");
      verifiees++;
      if (tm.verifierVenue(ancienVenueId) != StatutIdTM.MORT) {
        reinitialiserSuspect(salleId);
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
   * Signale les slugs Digitick suspects — pas de redécouverte automatique
   * (slugs manuels).
   */
  /* package-private : appelé par ReconciliationNocturneService (même
   * package). */
  void alerterDigitickSuspect() {
    final List<Map<String, Object>> suspectes =
        jdbc.queryForList(
            """
            SELECT ss.identifiant_externe, s.nom
            FROM salle_sources ss JOIN salles s ON s.id = ss.salle_id
            WHERE ss.source = 'digitick' AND ss.suspect_depuis IS NOT NULL
            """,
            Map.of());
    if (suspectes.isEmpty()) {
      return;
    }

    logsAdminWorker.inserer(
        NIVEAU_WARN,
        SOURCE_RECONCILIATION_NOCTURNE,
        suspectes.size()
            + " slug(s) Digitick suspect(s) — correction manuelle via l'onglet admin Salles",
        Map.of("nbSuspectes", suspectes.size()));
  }
}
