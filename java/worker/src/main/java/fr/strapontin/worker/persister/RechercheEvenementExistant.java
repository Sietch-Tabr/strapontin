/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker.persister;

import fr.strapontin.core.CorrespondanceArtiste;
import fr.strapontin.core.ProduitEventim;
import java.sql.Time;
import java.sql.Types;
import java.time.Duration;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * Événement canonique déjà en base pour un concert entrant : correspondance
 * exacte, sinon même salle et même date avec artiste doublon et heures
 * compatibles (fiches package Ticketmaster comprises).
 * @since 3.0.2
 * @author Kwaite
 */
final class RechercheEvenementExistant {

  // Écart d'heure toléré entre deux fiches TM d'un même événement, très en
  // dessous de tout écart matinée/soirée réel.
  private static final int TOLERANCE_HEURE_MINUTES = 3;
  // Écart d'heure toléré pour un même produit Eventim : au-delà, deux séances.
  private static final int ECART_MAX_PRODUIT_MINUTES = 60;
  private static final String CLE_DATE = "date";
  private static final String CLE_HEURE = "heure";

  private final NamedParameterJdbcTemplate jdbc;

  /* package-private : instancié par PersisteurConcertsDb. */
  RechercheEvenementExistant(final NamedParameterJdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /* package-private : appelé par PersisteurConcertsDb. */
  UUID exact(final ValeurConcert concert) {
    final MapSqlParameterSource paramsExact =
        new MapSqlParameterSource()
            .addValue("artisteNorm", concert.artisteNorm())
            .addValue(CLE_DATE, concert.date(), Types.DATE)
            .addValue(CLE_HEURE, concert.heure(), Types.TIME)
            .addValue(
                "salleId",
                concert.salleId() != null ? concert.salleId().toString() : null,
                Types.VARCHAR);
    final List<UUID> exact =
        jdbc.query(
            "SELECT id FROM evenements WHERE artiste_norm = :artisteNorm AND date = :date"
                + " AND heure IS NOT DISTINCT FROM :heure"
                + " AND salle_id IS NOT DISTINCT FROM CAST(:salleId AS uuid)",
            paramsExact,
            (rs, _) -> rs.getObject("id", UUID.class));
    return exact.isEmpty() ? null : exact.get(0);
  }

  /**
   * Événement qui porte déjà ce produit le même jour dans la même salle, quels
   * que soient le titre et l'heure publiés : produit Eventim (son identifiant
   * survit au titre, pas l'adresse) ou page d'un site de salle. Plusieurs
   * candidats : le mieux entretenu, la fusion nocturne rapproche les autres.
   * @return l'événement, ou {@code null} sans candidat ou si les artistes divergent
   */
  /* package-private : appelé par PersisteurConcertsDb. */
  UUID parProduit(final ValeurConcert concert) {
    final String condition = conditionProduit(concert);
    if (condition == null) {
      return null;
    }
    final List<Map<String, Object>> candidats =
        jdbc.queryForList(
            "SELECT e.id AS id, e.artiste_id AS artiste_id, e.heure AS heure"
                + " FROM observations o JOIN evenements e ON e.id = o.evenement_id"
                + " WHERE "
                + condition
                + " AND e.date = :date"
                + " AND (e.salle_id IS NULL OR CAST(:salleId AS uuid) IS NULL"
                + "      OR e.salle_id = CAST(:salleId AS uuid))"
                + " ORDER BY e.absences_consecutives, e.first_seen, e.id",
            new MapSqlParameterSource()
                .addValue("produit", ProduitEventim.identifiant(concert.url()).orElse(null))
                .addValue("url", concert.url())
                .addValue(CLE_DATE, concert.date(), Types.DATE)
                .addValue(
                    "salleId",
                    concert.salleId() != null ? concert.salleId().toString() : null,
                    Types.VARCHAR));
    for (final Map<String, Object> c : candidats) {
      final UUID candArtisteId = (UUID) c.get("artiste_id");
      final boolean artistesDivergents =
          concert.artisteId() != null
              && candArtisteId != null
              && !concert.artisteId().equals(candArtisteId);
      final Object heureBrute = c.get(CLE_HEURE);
      final LocalTime heure = heureBrute != null ? ((Time) heureBrute).toLocalTime() : null;
      if (!artistesDivergents && heureProche(heure, concert.heure())) {
        return (UUID) c.get("id");
      }
    }
    return null;
  }

  // Condition SQL des observations du même produit, null sans identifiant.
  private static String conditionProduit(final ValeurConcert concert) {
    if ("eventim".equals(concert.source())
        && ProduitEventim.identifiant(concert.url()).isPresent()) {
      return "o.source = 'eventim' AND " + ProduitEventim.expressionSql("o") + " = :produit";
    }
    if ("site_salle".equals(concert.source()) && concert.url() != null) {
      return "o.source = 'site_salle' AND o.url = :url";
    }
    return null;
  }

  private static boolean heureProche(final LocalTime existante, final LocalTime entrante) {
    return existante == null
        || entrante == null
        || Math.abs(Duration.between(existante, entrante).toMinutes()) <= ECART_MAX_PRODUIT_MINUTES;
  }

  // return en dernière position de boucle nécessaire : arrête la recherche au
  // premier candidat compatible plutôt que de poursuivre l'itération sans effet
  // utile.
  @SuppressWarnings("PMD.AvoidBranchingStatementAsLastInLoop")
  /* package-private : appelé par PersisteurConcertsDb. */
  UUID parSalleEtDate(final ValeurConcert concert) {
    final List<Map<String, Object>> candidats =
        jdbc.queryForList(
            """
            SELECT e.id AS id, e.artiste_id AS artiste_id, e.artiste_norm AS artiste_norm,
                   e.heure AS heure,
                   EXISTS(SELECT 1 FROM observations o WHERE o.evenement_id = e.id
                          AND o.url ~* '/pack/') AS a_observation_package
            FROM evenements e WHERE e.salle_id = CAST(:salleId AS uuid) AND e.date = :date
            """,
            Map.of("salleId", concert.salleId().toString(), CLE_DATE, concert.date()));
    final boolean entrantPackage = estPackage(concert.url());
    for (final Map<String, Object> c : candidats) {
      final Object heureBrute = c.get(CLE_HEURE);
      final LocalTime heureExistante =
          heureBrute != null ? ((Time) heureBrute).toLocalTime() : null;
      if (!artisteCorrespond(concert, c, heureExistante)) {
        continue;
      }
      final boolean candidatPackage = Boolean.TRUE.equals(c.get("a_observation_package"));
      if (!heuresCompatibles(heureExistante, concert.heure(), candidatPackage, entrantPackage)) {
        continue;
      }
      return (UUID) c.get("id");
    }
    return null;
  }

  /**
   * Même artiste : même {@code artiste_id} s'il est résolu ; sinon nom
   * doublon, ou même affiche quand l'heure est la même (co-plateau, suffixe
   * « 1ère partie » ajouté par une seule billetterie).
   */
  private static boolean artisteCorrespond(
      final ValeurConcert concert, final Map<String, Object> candidat, final LocalTime heure) {
    final UUID candArtisteId = (UUID) candidat.get("artiste_id");
    if (concert.artisteId() != null) {
      return concert.artisteId().equals(candArtisteId);
    }
    if (candArtisteId != null) {
      return false;
    }
    final String candArtisteNorm = (String) candidat.get("artiste_norm");
    return CorrespondanceArtiste.estDoublonArtiste(candArtisteNorm, concert.artisteNorm())
        || memeHeure(heure, concert.heure())
            && CorrespondanceArtiste.estMemeAffiche(candArtisteNorm, concert.artisteNorm());
  }

  private static boolean memeHeure(final LocalTime existante, final LocalTime entrante) {
    return existante != null
        && entrante != null
        && Math.abs(Duration.between(existante, entrante).toMinutes()) <= TOLERANCE_HEURE_MINUTES;
  }

  /**
   * Motif d'URL Ticketmaster signant un événement "package"/VIP — jamais
   * accessible à un achat de billetterie standard, ne doit jamais empêcher le
   * rapprochement avec la fiche normale du même événement réel.
   */
  private static boolean estPackage(final String url) {
    return url != null && url.matches("(?i).*\\Q/pack/\\E.*");
  }

  private static boolean heuresCompatibles(
      final LocalTime existante,
      final LocalTime entrante,
      final boolean packageExistant,
      final boolean packageEntrant) {
    if (packageExistant || packageEntrant) {
      return true;
    }
    if (existante == null || entrante == null) {
      return true;
    }
    final long diffMinutes = Math.abs(Duration.between(existante, entrante).toMinutes());
    return diffMinutes <= TOLERANCE_HEURE_MINUTES;
  }
}
