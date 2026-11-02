/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker.persister;

import fr.strapontin.core.ProduitEventim;
import fr.strapontin.worker.LogsAdminWorker;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Fusion nocturne des fiches portant le même produit Eventim : l'adresse change
 * avec le titre, pas l'identifiant du produit, et une fiche que Ticketmaster
 * entretient ne vieillit jamais.
 * @since 3.1.0
 * @author Kwaite
 */
@Service
public class FusionProduitsEventimService {

  // Écart d'heure maximal entre deux fiches d'un produit : au-delà, deux séances.
  private static final int ECART_MAX_HEURE_SECONDES = 3600;
  private static final long VIVANT_UNIQUE = 1;
  private static final String CLE_ECART_SECONDES = "ecartSecondes";

  private final NamedParameterJdbcTemplate jdbc;
  private final FusionEvenementsArtisteService fusionEvenements;
  private final LogsAdminWorker logsAdminWorker;

  /**
   * @param jdbc accès JDBC
   * @param fusionEvenements fusion d'une paire d'événements
   * @param logsAdminWorker trace des fiches marquées
   */
  public FusionProduitsEventimService(
      final NamedParameterJdbcTemplate jdbc,
      final FusionEvenementsArtisteService fusionEvenements,
      final LogsAdminWorker logsAdminWorker) {
    this.jdbc = jdbc;
    this.fusionEvenements = fusionEvenements;
    this.logsAdminWorker = logsAdminWorker;
  }

  /**
   * Fusionne les deux fiches d'un produit du même jour et de la même salle, à moins
   * d'une heure d'écart et sans deux artistes reconnus différents ; trois fiches ou
   * plus restent, signalées par le contrôle d'intégrité.
   * @return nombre de fusions effectuées
   */
  public int fusionner() {
    final String produit = ProduitEventim.expressionSql("o");
    final List<Map<String, Object>> paires =
        jdbc.queryForList(
            "WITH p AS (SELECT DISTINCT e.id, e.salle_id, e.date, e.heure, e.artiste_id, "
                + produit
                + " AS produit FROM observations o JOIN evenements e ON e.id = o.evenement_id"
                + " WHERE o.source = 'eventim' AND e.date >= CURRENT_DATE"
                + " AND e.salle_id IS NOT NULL AND "
                + produit
                + " IS NOT NULL)"
                + " SELECT a.id AS evt_a, b.id AS evt_b FROM p a JOIN p b"
                + " ON b.produit = a.produit AND b.date = a.date AND b.salle_id = a.salle_id"
                + " AND a.id < b.id"
                + " WHERE (a.heure IS NULL OR b.heure IS NULL"
                + " OR abs(extract(epoch FROM (a.heure - b.heure))) <= :ecartSecondes)"
                + " AND (a.artiste_id IS NULL OR b.artiste_id IS NULL"
                + " OR a.artiste_id = b.artiste_id)"
                + " AND (SELECT count(*) FROM p x WHERE x.produit = a.produit"
                + " AND x.date = a.date AND x.salle_id = a.salle_id) = 2",
            Map.of(CLE_ECART_SECONDES, ECART_MAX_HEURE_SECONDES));
    int fusions = 0;
    for (final Map<String, Object> paire : paires) {
      final UUID evtA = (UUID) paire.get("evt_a");
      final UUID evtB = (UUID) paire.get("evt_b");
      final String libelleRecent = libelleLePlusRecent(evtA, evtB);
      final UUID conservee =
          fusionEvenements.fusionnerPaireChoisie(evtA, evtB, "même produit Eventim");
      if (conservee != null) {
        retirerObservationsPerimees(conservee);
        aligner(conservee, libelleRecent);
        fusions++;
      }
    }
    return fusions;
  }

  /**
   * Fiche d'un produit Eventim republié à une autre date ou à une autre heure :
   * l'ancienne fiche, absente de sa propre séance et sans autre billetterie qui
   * l'entretienne, rejoint l'unique fiche vivante du même produit dans la même
   * salle. Une fiche maintenue par un administrateur reste.
   * @return nombre de fiches rattachées
   */
  public int fusionnerProduitsRepublies() {
    final String produit = ProduitEventim.expressionSql("o");
    final List<Map<String, Object>> paires =
        jdbc.queryForList(
            "WITH p AS (SELECT DISTINCT e.id, e.salle_id, e.date, e.heure, e.artiste_id,"
                + " e.absences_consecutives, e.maintenu_le, "
                + produit
                + " AS produit FROM observations o JOIN evenements e ON e.id = o.evenement_id"
                + " WHERE o.source = 'eventim' AND e.date >= CURRENT_DATE"
                + " AND e.salle_id IS NOT NULL AND "
                + produit
                + " IS NOT NULL)"
                + " SELECT a.id AS perime, min(b.id::text) AS vivant,"
                + " count(DISTINCT b.id) AS nb_vivants FROM p a JOIN p b"
                + " ON b.produit = a.produit AND b.salle_id = a.salle_id AND b.id <> a.id"
                + " AND (b.date <> a.date OR abs(extract(epoch FROM (a.heure - b.heure)))"
                + " > :ecartSecondes)"
                + " WHERE a.absences_consecutives >= :seuil AND b.absences_consecutives = 0"
                + " AND a.maintenu_le IS NULL"
                + " AND (a.artiste_id IS NULL OR b.artiste_id IS NULL"
                + " OR a.artiste_id = b.artiste_id)"
                + " AND NOT EXISTS (SELECT 1 FROM observations t"
                + " WHERE t.evenement_id = a.id AND t.source <> 'eventim')"
                + " GROUP BY a.id",
            Map.of(
                CLE_ECART_SECONDES,
                ECART_MAX_HEURE_SECONDES,
                "seuil",
                FusionEvenementsArtisteService.SEUIL_ABSENCES_FICHE_PERIMEE));
    int fusions = 0;
    for (final Map<String, Object> ligne : paires) {
      if (((Number) ligne.get("nb_vivants")).longValue() != VIVANT_UNIQUE) {
        continue;
      }
      final UUID vivant = UUID.fromString((String) ligne.get("vivant"));
      fusionEvenements.fusionnerDansFicheVivante(
          vivant, (UUID) ligne.get("perime"), "produit Eventim republié");
      retirerObservationsPerimees(vivant);
      fusions++;
    }
    return fusions;
  }

  /**
   * Concert qu'Eventim republie dans une autre salle sous un autre produit :
   * l'ancienne fiche, que seul Eventim entretenait et qu'il n'a plus renvoyée
   * depuis l'arrivée de la nouvelle, rejoint l'unique fiche vivante du même
   * artiste, du même jour et du même département, à une heure voisine. Une
   * fiche maintenue par un administrateur reste.
   * @return nombre de fiches rattachées
   */
  public int fusionnerProduitsRepubliesAilleurs() {
    final List<Map<String, Object>> lignes =
        jdbc.queryForList(
            """
            WITH p AS (
              SELECT e.id, e.salle_id, e.date, e.heure, e.artiste_norm, e.artiste_id,
                     e.maintenu_le, e.absences_consecutives,
                     bool_and(o.source = 'eventim') AS eventim_seul,
                     max(o.updated_at) AS derniere_vue,
                     max(o.first_seen) FILTER (WHERE o.source = 'eventim') AS derniere_arrivee
              FROM evenements e JOIN observations o ON o.evenement_id = e.id
              WHERE e.date >= CURRENT_DATE AND e.salle_id IS NOT NULL
              GROUP BY e.id)
            SELECT a.id AS perime, min(b.id::text) AS vivant, count(DISTINCT b.id) AS nb_vivants
            FROM p a
            JOIN salles sa ON sa.id = a.salle_id
            JOIN p b ON b.id <> a.id AND b.date = a.date AND b.salle_id <> a.salle_id
             AND (b.artiste_norm = a.artiste_norm
                  OR (a.artiste_id IS NOT NULL AND b.artiste_id = a.artiste_id))
             AND (a.heure IS NULL OR b.heure IS NULL
                  OR abs(extract(epoch FROM (a.heure - b.heure))) <= :ecartSecondes)
            JOIN salles sb ON sb.id = b.salle_id AND sb.dept = sa.dept
            WHERE a.eventim_seul AND a.maintenu_le IS NULL
              AND a.derniere_vue < now() - interval '1 day'
              AND b.absences_consecutives = 0 AND b.derniere_arrivee > a.derniere_vue
            GROUP BY a.id
            """,
            Map.of(CLE_ECART_SECONDES, ECART_MAX_HEURE_SECONDES));
    int fusions = 0;
    for (final Map<String, Object> ligne : lignes) {
      if (((Number) ligne.get("nb_vivants")).longValue() != VIVANT_UNIQUE) {
        continue;
      }
      fusionEvenements.fusionnerDansFicheVivante(
          UUID.fromString((String) ligne.get("vivant")),
          (UUID) ligne.get("perime"),
          "produit Eventim republié dans une autre salle");
      fusions++;
    }
    return fusions;
  }

  // Intitulé de la fiche à la publication Eventim la plus récente : la fiche
  // gardée peut porter une première publication fautive.
  private String libelleLePlusRecent(final UUID evtA, final UUID evtB) {
    final List<String> libelles =
        jdbc.queryForList(
            "SELECT e.artiste_norm FROM evenements e WHERE e.id IN (:a, :b)"
                + " ORDER BY (SELECT max(o.updated_at) FROM observations o"
                + " WHERE o.evenement_id = e.id AND o.source = 'eventim') DESC NULLS LAST,"
                + " e.first_seen DESC LIMIT 1",
            Map.of("a", evtA, "b", evtB),
            String.class);
    return libelles.isEmpty() ? null : libelles.get(0);
  }

  // Sans artiste reconnu, l'intitulé fait l'identité de la fiche ; avec un
  // artiste, elle garde le sien. Un même nom au même créneau l'interdit.
  private void aligner(final UUID conservee, final String libelle) {
    if (libelle == null) {
      return;
    }
    jdbc.update(
        """
        UPDATE evenements e SET artiste_norm = :libelle, updated_at = now()
        WHERE e.id = :id AND e.artiste_id IS NULL AND e.artiste_norm <> :libelle
          AND NOT EXISTS (
            SELECT 1 FROM evenements x
            WHERE x.id <> e.id AND x.artiste_norm = :libelle AND x.date = e.date
              AND x.heure IS NOT DISTINCT FROM e.heure
              AND x.salle_id IS NOT DISTINCT FROM e.salle_id)
        """,
        Map.of("id", conservee, "libelle", libelle));
  }

  // Après un changement de titre, l'ancienne adresse d'un produit reste portée par
  // la fiche conservée sans jamais être revue : seule la plus récente est gardée.
  private void retirerObservationsPerimees(final UUID evenementId) {
    jdbc.update(
        "DELETE FROM observations o USING observations n"
            + " WHERE o.evenement_id = CAST(:id AS uuid) AND n.evenement_id = o.evenement_id"
            + " AND o.source = 'eventim' AND n.source = 'eventim'"
            + " AND "
            + ProduitEventim.expressionSql("o")
            + " = "
            + ProduitEventim.expressionSql("n")
            + " AND (o.updated_at < n.updated_at"
            + " OR (o.updated_at = n.updated_at AND o.id < n.id))",
        Map.of("id", evenementId.toString()));
  }

  /**
   * Fiche d'origine d'une date reportée : Ticketmaster a déplacé la séance (statut
   * reporté, nouvelle date) alors qu'une autre billetterie publie encore la date
   * d'origine. Seule fiche de la salle et de l'artiste à une heure voisine, au
   * moins un mois avant le report, elle passe indisponible plutôt que d'être
   * affichée comme un concert distinct.
   * @return nombre de fiches d'origine marquées
   */
  public int marquerFichesDOrigineDesReports() {
    final int marquees =
        jdbc.update(
            """
            WITH t AS (
              SELECT e.id, e.salle_id, e.artiste_id, e.artiste_norm, e.date, e.heure
              FROM evenements e
              WHERE e.statut = 'rescheduled' AND e.date >= CURRENT_DATE
                AND e.salle_id IS NOT NULL
                AND EXISTS (SELECT 1 FROM observations o
                            WHERE o.evenement_id = e.id AND o.source = 'ticketmaster')),
            origine AS (
              SELECT e.id, e.salle_id, e.artiste_id, e.artiste_norm, e.date, e.heure
              FROM evenements e
              WHERE e.date >= CURRENT_DATE AND e.statut IS DISTINCT FROM 'cancelled'
                AND e.salle_id IS NOT NULL
                AND NOT EXISTS (SELECT 1 FROM observations o
                                WHERE o.evenement_id = e.id AND o.source = 'ticketmaster')),
            paires AS (
              SELECT o.id AS origine, t.id AS reporte
              FROM t JOIN origine o ON o.salle_id = t.salle_id
               AND o.date <= t.date - 30 AND o.date > t.date - 800
               AND (o.heure IS NULL OR t.heure IS NULL
                    OR abs(extract(epoch FROM (o.heure - t.heure))) <= :ecartSecondes)
               AND ((o.artiste_id IS NOT NULL AND o.artiste_id = t.artiste_id)
                    OR o.artiste_norm = t.artiste_norm))
            UPDATE evenements SET statut = 'cancelled'
            WHERE id IN (
              SELECT p.origine FROM paires p
              WHERE (SELECT count(*) FROM paires q WHERE q.origine = p.origine) = 1
                AND (SELECT count(*) FROM paires q WHERE q.reporte = p.reporte) = 1)
            """,
            Map.of(CLE_ECART_SECONDES, ECART_MAX_HEURE_SECONDES));
    if (marquees > 0) {
      logsAdminWorker.inserer(
          "info",
          "reconciliation-nocturne",
          marquees + " fiche(s) d'origine d'une date reportée marquée(s) indisponible(s)",
          Map.of("fiches", marquees));
    }
    return marquees;
  }
}
