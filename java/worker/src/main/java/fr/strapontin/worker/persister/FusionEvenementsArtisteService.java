/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker.persister;

import fr.strapontin.core.CorrespondanceArtiste;
import fr.strapontin.worker.LogsAdminWorker;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Fusion nocturne d'événements persistés séparément : artiste_norm convergé
 * après coup (la réconciliation ne dédoublonne qu'à la création), ou fiche
 * périmée d'un événement encore observé.
 * @since 3.0.0
 * @author Kwaite
 */
@Service
public class FusionEvenementsArtisteService {

  private static final long VIVANT_UNIQUE = 1;
  private static final Logger LOG = LoggerFactory.getLogger(FusionEvenementsArtisteService.class);
  private static final String CLE_CIBLE = "cible";
  private static final String CLE_SOURCE = "source";
  private static final int NB_EVENEMENTS_PAIRE = 2;
  // Absences au-delà desquelles une fiche n'est plus vivante (un cycle de
  // fetch manqué ponctuellement ne suffit pas).
  /* package-private : visibilité élargie pour les tests (même package). */
  static final int SEUIL_ABSENCES_FICHE_PERIMEE = 3;
  // Écart d'heure maximal entre l'ancienne et la nouvelle fiche d'une même
  // séance : au-delà, ce sont deux séances (matinée et soirée).
  private static final int ECART_MAX_HEURE_CHANGEE_SECONDES = 3600;

  private final NamedParameterJdbcTemplate jdbc;
  private final LogsAdminWorker logsAdminWorker;

  private record PaireEvenements(UUID idA, UUID idB) {}

  /**
   * @param jdbc accès JDBC
   * @param logsAdminWorker journalisation de chaque fusion effectuée
   */
  public FusionEvenementsArtisteService(
      final NamedParameterJdbcTemplate jdbc, final LogsAdminWorker logsAdminWorker) {
    this.jdbc = jdbc;
    this.logsAdminWorker = logsAdminWorker;
  }

  /**
   * Détecte puis fusionne les paires d'événements partageant salle/date/
   * heure avec un {@code artiste_norm} doublon flou — une paire déjà
   * disparue (fusionnée plus tôt dans le même cycle) est ignorée.
   * @return nombre de fusions effectuées
   */
  public int fusionner() {
    final List<PaireEvenements> paires = detecterPaires();
    final Set<UUID> dejaAbsorbes = new HashSet<>();
    int fusions = 0;
    for (final PaireEvenements paire : paires) {
      if (dejaAbsorbes.contains(paire.idA()) || dejaAbsorbes.contains(paire.idB())) {
        continue;
      }
      final UUID[] cibleSource = choisirCibleEtSource(paire.idA(), paire.idB());
      if (cibleSource == null) {
        continue;
      }
      fusionnerUnePaire(cibleSource[0], cibleSource[1]);
      dejaAbsorbes.add(cibleSource[1]);
      fusions++;
    }
    return fusions;
  }

  /**
   * Fusionne chaque événement périmé dans l'unique événement vivant de même
   * fiche (même url, ou même idmanif TM à la même date, apparue plus d'une
   * heure après la dernière vue de la périmée : deux séances vues au même
   * passage restent distinctes) ; ambigu : ignoré. Avant la fusion de salles.
   * @return nombre de fusions effectuées
   */
  public int fusionnerFichesPerimees() {
    final List<Map<String, Object>> lignes =
        jdbc.queryForList(
            """
            WITH obs AS (
              SELECT o.evenement_id, o.source, o.url, e.date, e.absences_consecutives,
                     o.first_seen, o.updated_at,
                     CASE WHEN o.source = 'ticketmaster'
                          THEN substring(o.url FROM '/idmanif/([0-9]+)') END AS idmanif
              FROM observations o
              JOIN evenements e ON e.id = o.evenement_id
              WHERE o.url IS NOT NULL AND e.date >= CURRENT_DATE
            )
            SELECT p.evenement_id AS perime, min(v.evenement_id::text) AS vivant,
                   count(DISTINCT v.evenement_id) AS nb_vivants
            FROM obs p
            JOIN obs v
              ON v.evenement_id <> p.evenement_id
             AND v.source = p.source
             AND (v.url = p.url OR (p.idmanif IS NOT NULL AND v.idmanif = p.idmanif
                                    AND v.date = p.date
                                    AND p.updated_at < v.first_seen - interval '1 hour'))
            WHERE p.absences_consecutives >= :seuil AND v.absences_consecutives = 0
            GROUP BY p.evenement_id
            """,
            Map.of("seuil", SEUIL_ABSENCES_FICHE_PERIMEE));
    return fusionnerSiVivantUnique(lignes, "fiche source périmée");
  }

  /**
   * Séance dont l'heure a changé : la fiche qui n'est plus publiée (absente
   * depuis plusieurs cycles) rejoint l'unique fiche vivante du même artiste, de
   * la même salle et du même jour, à moins d'une heure d'écart — quelle que soit
   * la source de l'une ou l'autre. Deux séances réelles d'un même jour (matinée,
   * soirée) sont plus éloignées ; plusieurs vivantes possibles : ignoré.
   * @return nombre de fusions effectuées
   */
  public int fusionnerSeancesHeureChangee() {
    final List<Map<String, Object>> lignes =
        jdbc.queryForList(
            """
            SELECT p.id AS perime, min(v.id::text) AS vivant, count(DISTINCT v.id) AS nb_vivants
            FROM evenements p
            JOIN evenements v
              ON v.id <> p.id
             AND v.salle_id = p.salle_id
             AND v.date = p.date
             AND (v.artiste_norm = p.artiste_norm
                  OR (v.artiste_id IS NOT NULL AND v.artiste_id = p.artiste_id))
             AND v.heure IS NOT NULL AND p.heure IS NOT NULL
             AND abs(extract(epoch FROM (v.heure - p.heure))) <= :ecartSecondes
            WHERE p.date >= CURRENT_DATE AND p.salle_id IS NOT NULL
              AND p.absences_consecutives >= :seuil AND v.absences_consecutives = 0
            GROUP BY p.id
            """,
            Map.of(
                "seuil", SEUIL_ABSENCES_FICHE_PERIMEE,
                "ecartSecondes", ECART_MAX_HEURE_CHANGEE_SECONDES));
    return fusionnerSiVivantUnique(lignes, "séance dont l'heure a changé");
  }

  /**
   * Même séance publiée par deux billetteries à des heures proches (20 h 30 chez
   * Ticketmaster, 20 h chez Eventim) : seuls événements de leur artiste, salle et
   * jour, à moins d'une heure d'écart, l'un ne portant qu'une source et les sources
   * différant, dans une salle non signalée comme mélangeant plusieurs lieux.
   * @return nombre de fusions effectuées
   */
  public int fusionnerMemeSeanceDeuxSourcesHeuresProches() {
    final List<Map<String, Object>> paires =
        jdbc.queryForList(
            """
            WITH ev AS (
              SELECT e.id, e.salle_id, e.date, e.heure, e.artiste_norm,
                     bool_or(o.source = 'ticketmaster') AS tm, bool_or(o.source = 'eventim') AS evt
              FROM evenements e JOIN observations o ON o.evenement_id = e.id
              WHERE e.date >= CURRENT_DATE AND e.salle_id IS NOT NULL AND e.heure IS NOT NULL
              GROUP BY e.id),
            groupes AS (
              SELECT salle_id, date, artiste_norm FROM ev WHERE tm OR evt
              GROUP BY salle_id, date, artiste_norm HAVING count(*) = 2)
            SELECT a.id AS evt_a, b.id AS evt_b,
                   (a.tm AND NOT a.evt AND b.evt AND NOT b.tm) AS a_ticketmaster_seul,
                   (b.tm AND NOT b.evt AND a.evt AND NOT a.tm) AS b_ticketmaster_seul
            FROM groupes g
            JOIN salles s ON s.id = g.salle_id
            JOIN ev a ON a.salle_id = g.salle_id AND a.date = g.date
                     AND a.artiste_norm = g.artiste_norm AND (a.tm OR a.evt)
            JOIN ev b ON b.salle_id = g.salle_id AND b.date = g.date
                     AND b.artiste_norm = g.artiste_norm AND (b.tm OR b.evt) AND a.id < b.id
            WHERE abs(extract(epoch FROM (a.heure - b.heure))) <= :ecartSecondes
              AND (a.tm::int + a.evt::int = 1 OR b.tm::int + b.evt::int = 1)
              AND NOT (a.tm = b.tm AND a.evt = b.evt)
              AND\
            """
                + SalleFiable.condition("s"),
            Map.of("ecartSecondes", ECART_MAX_HEURE_CHANGEE_SECONDES));
    int fusions = 0;
    for (final Map<String, Object> paire : paires) {
      final UUID idA = (UUID) paire.get("evt_a");
      final UUID idB = (UUID) paire.get("evt_b");
      final String motif = "même séance, deux sources, heures proches";
      if (Boolean.TRUE.equals(paire.get("a_ticketmaster_seul"))) {
        fusionner(idA, idB, motif);
      } else if (Boolean.TRUE.equals(paire.get("b_ticketmaster_seul"))) {
        fusionner(idB, idA, motif);
      } else if (fusionnerPaireChoisie(idA, idB, motif) == null) {
        continue;
      }
      fusions++;
    }
    return fusions;
  }

  /**
   * Concert déplacé : la fiche absente de l'ancienne salle rejoint l'unique
   * événement vivant de même artiste, date, heure, département et source dans
   * une autre salle ; ambigu, ou salle publiant encore la manifestation
   * (idmanif, à défaut URL) : ignoré.
   * @return nombre de fusions effectuées
   */
  public int fusionnerConcertsDeplaces() {
    final List<Map<String, Object>> lignes =
        jdbc.queryForList(
            """
            SELECT p.id AS perime, min(v.id::text) AS vivant, count(DISTINCT v.id) AS nb_vivants
            FROM evenements p
            JOIN evenements v
              ON v.id <> p.id
             AND v.artiste_norm = p.artiste_norm
             AND v.date = p.date
             AND v.heure = p.heure
             AND v.dept = p.dept
             AND v.salle_id IS DISTINCT FROM p.salle_id
            WHERE p.date >= CURRENT_DATE
              AND p.absences_consecutives >= :seuil AND v.absences_consecutives = 0
              AND EXISTS (SELECT 1 FROM observations op JOIN observations ov
                            ON ov.source = op.source
                          WHERE op.evenement_id = p.id AND ov.evenement_id = v.id)
              AND NOT EXISTS (
                SELECT 1 FROM observations op
                JOIN observations os
                  ON os.source = op.source
                 AND COALESCE(substring(os.url FROM '/idmanif/([0-9]+)'), os.url)
                   = COALESCE(substring(op.url FROM '/idmanif/([0-9]+)'), op.url)
                JOIN evenements es ON es.id = os.evenement_id
                WHERE op.evenement_id = p.id AND es.id <> p.id
                  AND es.salle_id = p.salle_id AND es.absences_consecutives = 0)
            GROUP BY p.id
            """,
            Map.of("seuil", SEUIL_ABSENCES_FICHE_PERIMEE));
    return fusionnerSiVivantUnique(lignes, "concert déplacé");
  }

  /**
   * Même spectacle publié sous deux intitulés (« Lady Macbeth de Mzensk » /
   * « of Mtsensk », « Ballet Preljocaj » / « Le Lac des cygnes ») : seuls
   * événements de leur salle au même créneau, l'un Ticketmaster seul, l'autre
   * Eventim seul, sans artiste reconnu, dans une salle non signalée comme
   * mélangeant plusieurs lieux. À appeler après les contrôles d'intégrité.
   * @return nombre de fusions effectuées
   */
  public int fusionnerMemeCreneauDeuxSources() {
    final List<Map<String, Object>> paires =
        jdbc.queryForList(
            """
            WITH ev AS (
              SELECT e.id, e.salle_id, e.date, e.heure, e.artiste_id,
                     bool_or(o.source = 'ticketmaster') AS tm, bool_or(o.source = 'eventim') AS evt
              FROM evenements e JOIN observations o ON o.evenement_id = e.id
              WHERE e.date >= CURRENT_DATE AND e.salle_id IS NOT NULL AND e.heure IS NOT NULL
              GROUP BY e.id),
            creneaux AS (
              SELECT salle_id, date, heure FROM ev GROUP BY salle_id, date, heure
              HAVING count(*) = 2
                 AND count(*) FILTER (WHERE tm AND NOT evt) = 1
                 AND count(*) FILTER (WHERE evt AND NOT tm) = 1
                 AND count(*) FILTER (WHERE artiste_id IS NOT NULL) = 0)
            SELECT t.id AS cible, v.id AS source
            FROM creneaux c
            JOIN salles s ON s.id = c.salle_id
            JOIN ev t ON t.salle_id = c.salle_id AND t.date = c.date AND t.heure = c.heure
                     AND t.tm AND NOT t.evt
            JOIN ev v ON v.salle_id = c.salle_id AND v.date = c.date AND v.heure = c.heure
                     AND v.evt AND NOT v.tm
            WHERE\
            """
                + SalleFiable.condition("s"),
            Map.of());
    for (final Map<String, Object> paire : paires) {
      fusionner(
          (UUID) paire.get("cible"), (UUID) paire.get("source"), "même créneau, deux sources");
    }
    return paires.size();
  }

  // Plusieurs vivants (même adresse pour plusieurs séances) : aucun choix sûr.
  private int fusionnerSiVivantUnique(final List<Map<String, Object>> lignes, final String motif) {
    int fusions = 0;
    int ambigues = 0;
    for (final Map<String, Object> ligne : lignes) {
      if (((Number) ligne.get("nb_vivants")).longValue() != VIVANT_UNIQUE) {
        ambigues++;
        continue;
      }
      fusionner(UUID.fromString((String) ligne.get("vivant")), (UUID) ligne.get("perime"), motif);
      fusions++;
    }
    if (ambigues > 0) {
      LOG.info(
          "{} : {} fiche(s) rapprochée(s) de plusieurs événements vivants, ignorée(s)",
          motif,
          ambigues);
    }
    return fusions;
  }

  /**
   * Paires d'événements de même salle, date et heure aux artistes doublons
   * ({@link CorrespondanceArtiste#estDoublonArtiste}), ou de même affiche
   * ({@link CorrespondanceArtiste#estMemeAffiche}) sans artiste rattaché et à
   * heure connue ; {@code uq_evenements} exclut déjà un nom identique.
   */
  private List<PaireEvenements> detecterPaires() {
    final List<Map<String, Object>> lignes =
        jdbc.queryForList(
            """
            SELECT DISTINCT e1.id AS evt_a, e2.id AS evt_b,
                   e1.artiste_norm AS artiste_a, e2.artiste_norm AS artiste_b,
                   e1.heure IS NOT NULL AND e1.artiste_id IS NULL AND e2.artiste_id IS NULL
                     AS affiche_comparable
            FROM evenements e1
            JOIN evenements e2
              ON e1.salle_id = e2.salle_id
             AND e1.date = e2.date
             AND e1.heure IS NOT DISTINCT FROM e2.heure
             AND e1.artiste_norm <> e2.artiste_norm
             AND e1.id < e2.id
            WHERE e1.salle_id IS NOT NULL
              AND e1.date >= CURRENT_DATE
            """,
            Map.of());
    final List<PaireEvenements> paires = new ArrayList<>();
    for (final Map<String, Object> ligne : lignes) {
      final String artisteA = (String) ligne.get("artiste_a");
      final String artisteB = (String) ligne.get("artiste_b");
      // Deux artistes suivis séparément gardent chacun leur événement.
      final boolean memeAffiche =
          Boolean.TRUE.equals(ligne.get("affiche_comparable"))
              && CorrespondanceArtiste.estMemeAffiche(artisteA, artisteB);
      if (!memeAffiche && !CorrespondanceArtiste.estDoublonArtiste(artisteA, artisteB)) {
        continue;
      }
      paires.add(new PaireEvenements((UUID) ligne.get("evt_a"), (UUID) ligne.get("evt_b")));
    }
    return paires;
  }

  /**
   * Choisit l'événement à conserver (cible) entre les deux membres de la
   * paire : priorité à l'{@code artiste_id} déjà résolu (donnée coûteuse
   * à reconstituer), puis au nombre d'observations, puis au nom d'artiste le
   * plus court, puis à l'UUID le plus petit (résultat déterministe).
   * @return [cible, source], ou {@code null} si l'un des deux n'existe plus
   */
  // Le tableau représente une paire cible/source, pas une collection à agréger
  // — l'appelant teste explicitement null pour distinguer "événement disparu"
  // d'un tableau vide, sémantiques différentes.
  @SuppressWarnings("PMD.ReturnEmptyCollectionRatherThanNull")
  private UUID[] choisirCibleEtSource(final UUID idA, final UUID idB) {
    final List<Map<String, Object>> lignes =
        jdbc.queryForList(
            """
            SELECT e.id AS id, e.artiste_id AS artiste_id, e.artiste_norm AS artiste_norm,
                   (SELECT COUNT(*) FROM observations o WHERE o.evenement_id = e.id) AS nb_obs
            FROM evenements e WHERE e.id IN (CAST(:a AS uuid), CAST(:b AS uuid))
            """,
            Map.of("a", idA.toString(), "b", idB.toString()));
    if (lignes.size() < NB_EVENEMENTS_PAIRE) {
      return null;
    }

    // Le nom de la tête d'affiche plutôt que « orelsan premiere partie » : c'est
    // lui qui rattachera l'événement à l'artiste s'il est suivi plus tard.
    final List<Map<String, Object>> parPriorite =
        lignes.stream()
            .sorted(
                Comparator.comparing((Map<String, Object> l) -> l.get("artiste_id") == null)
                    .thenComparing(l -> -((Number) l.get("nb_obs")).longValue())
                    .thenComparingInt(l -> ((String) l.get("artiste_norm")).length())
                    .thenComparing(l -> (UUID) l.get("id")))
            .toList();
    return new UUID[] {(UUID) parPriorite.get(0).get("id"), (UUID) parPriorite.get(1).get("id")};
  }

  /**
   * Fusionne deux événements établis (réassigne observations/user_matches,
   * récupère l'{@code artiste_id} manquant, supprime la source). Public :
   * réutilisable pour une remédiation ponctuelle ciblée.
   * @param cibleId événement à conserver
   * @param sourceId événement à absorber puis supprimer
   */
  @Transactional
  public void fusionnerUnePaire(final UUID cibleId, final UUID sourceId) {
    fusionner(cibleId, sourceId, "artiste_norm convergent");
  }

  /**
   * Fusionne deux événements en gardant le mieux établi.
   * @param idA premier événement
   * @param idB second événement
   * @param motif raison journalisée
   * @return l'événement conservé, ou {@code null} si l'un a disparu
   */
  public UUID fusionnerPaireChoisie(final UUID idA, final UUID idB, final String motif) {
    final UUID[] cibleSource = choisirCibleEtSource(idA, idB);
    if (cibleSource == null) {
      return null;
    }
    fusionner(cibleSource[0], cibleSource[1], motif);
    return cibleSource[0];
  }

  // Observations et user_matches reportés sur la cible (doublons écartés),
  // jamais laissés au CASCADE de la source, qui perdrait un match notifié.
  private void reporterObservationsEtCorrespondances(final Map<String, Object> ids) {
    // 1. Observations en conflit supprimées, le reste réassigné à la
    // cible.
    jdbc.update(
        """
        DELETE FROM observations o1
        USING observations o2
        WHERE o1.evenement_id = CAST(:source AS uuid) AND o2.evenement_id = CAST(:cible AS uuid)
          AND o1.source = o2.source AND o1.url IS NOT DISTINCT FROM o2.url
        """,
        ids);
    jdbc.update(
        "UPDATE observations SET evenement_id = CAST(:cible AS uuid)"
            + " WHERE evenement_id = CAST(:source AS uuid)",
        ids);
    jdbc.update(
        """
        INSERT INTO evenement_artistes (evenement_id, artiste_norm, artiste_nom, artiste_id)
        SELECT CAST(:cible AS uuid), artiste_norm, artiste_nom, artiste_id
        FROM evenement_artistes WHERE evenement_id = CAST(:source AS uuid)
        ON CONFLICT DO NOTHING
        """,
        ids);

    // 2. user_matches : conflits déjà côté cible supprimés, le reste
    // réassigné — jamais laissé à la suppression CASCADE de la source
    // (perdrait un match déjà notifié).
    jdbc.update(
        """
        DELETE FROM user_matches um1
        USING user_matches um2
        WHERE um1.concert_id = CAST(:source AS uuid) AND um2.concert_id = CAST(:cible AS uuid)
          AND um1.user_id = um2.user_id
        """,
        ids);
    jdbc.update(
        "UPDATE user_matches SET concert_id = CAST(:cible AS uuid)"
            + " WHERE concert_id = CAST(:source AS uuid)",
        ids);
  }

  private void fusionner(final UUID cibleId, final UUID sourceId, final String motif) {
    final Map<String, Object> ids =
        Map.of(CLE_CIBLE, cibleId.toString(), CLE_SOURCE, sourceId.toString());

    reporterObservationsEtCorrespondances(ids);

    // 3. artiste_id manquant récupéré depuis la source si la cible ne l'a pas déjà.
    jdbc.update(
        """
        UPDATE evenements SET artiste_id = COALESCE(artiste_id,
            (SELECT artiste_id FROM evenements WHERE id = CAST(:source AS uuid)))
        WHERE id = CAST(:cible AS uuid)
        """,
        ids);

    // 4. Suppression de la source (CASCADE sur observations/
    // user_matches restants, déjà vidés par les étapes précédentes).
    jdbc.update("DELETE FROM evenements WHERE id = CAST(:source AS uuid)", ids);

    LOG.info(
        "reconciliation:nocturne — événements fusionnés ({}) cible={} source={}",
        motif,
        cibleId,
        sourceId);
    // Fusion automatique et destructive : jamais silencieuse.
    logsAdminWorker.inserer(
        "info",
        "reconciliation-nocturne",
        "événements fusionnés automatiquement ("
            + motif
            + ") cible="
            + cibleId
            + " source="
            + sourceId,
        Map.of(
            "evenementCible",
            cibleId.toString(),
            "evenementSource",
            sourceId.toString(),
            "motif",
            motif));
  }
}
