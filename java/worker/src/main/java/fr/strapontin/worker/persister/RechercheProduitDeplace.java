/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker.persister;

import fr.strapontin.core.ProduitEventim;
import java.sql.Date;
import java.sql.Types;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * Fiche qui porte déjà un produit Eventim dont la date ou l'heure a changé.
 * @since 3.1.4
 * @author Kwaite
 */
final class RechercheProduitDeplace {

  // Un produit revu depuis moins de ce délai est encore publié à sa date : pas
  // un produit déplacé.
  private static final int MINUTES_SANS_REVUE_PRODUIT_DEPLACE = 90;
  private static final String CLE_SALLE_ID = "salleId";
  private static final int UN_SEUL_CANDIDAT = 1;

  private final NamedParameterJdbcTemplate jdbc;

  /* package-private : instancié par RechercheEvenementExistant. */
  RechercheProduitDeplace(final NamedParameterJdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  // Le produit Eventim est l'identité de la fiche : un produit dont la date ou
  // l'heure change reste sur sa fiche. Seul un produit unique du lot (jamais
  // une série de séances), porté par une seule fiche de la salle et qu'aucun
  // cycle récent n'a revue (l'ancienne date n'est plus publiée), est déplacé.
  /* package-private : appelé par RechercheEvenementExistant (même package). */
  UUID chercher(final ValeurConcert concert) {
    final Optional<String> produit =
        "eventim".equals(concert.source())
            ? ProduitEventim.identifiant(concert.url())
            : Optional.empty();
    if (produit.isEmpty()) {
      return null;
    }
    final List<Map<String, Object>> candidats = candidats(produit.get(), concert);
    return candidats.size() == UN_SEUL_CANDIDAT && peutSuivre(candidats.get(0), concert)
        ? (UUID) candidats.get(0).get("id")
        : null;
  }

  private List<Map<String, Object>> candidats(final String produit, final ValeurConcert concert) {
    return jdbc.queryForList(
        "SELECT e.id AS id, e.artiste_id AS artiste_id, e.date AS date,"
            + " o.updated_at < now() - interval '"
            + MINUTES_SANS_REVUE_PRODUIT_DEPLACE
            + " minutes' AS ancienne,"
            + " NOT EXISTS (SELECT 1 FROM observations x WHERE x.evenement_id = e.id"
            + "   AND (x.source <> 'eventim' OR "
            + ProduitEventim.expressionSql("x")
            + " IS DISTINCT FROM :produit)) AS seul_produit"
            + " FROM observations o JOIN evenements e ON e.id = o.evenement_id"
            + " WHERE o.source = 'eventim' AND "
            + ProduitEventim.expressionSql("o")
            + " = :produit AND e.date >= CURRENT_DATE"
            + " AND (e.salle_id IS NULL OR CAST(:salleId AS uuid) IS NULL"
            + "      OR e.salle_id = CAST(:salleId AS uuid))",
        new MapSqlParameterSource()
            .addValue("produit", produit)
            .addValue(
                CLE_SALLE_ID,
                concert.salleId() != null ? concert.salleId().toString() : null,
                Types.VARCHAR));
  }

  // Même jour : la fiche garde l'identité que lui donne l'autre billetterie ;
  // autre jour : seul ce produit l'alimente, sa date peut le suivre.
  private static boolean peutSuivre(
      final Map<String, Object> candidat, final ValeurConcert concert) {
    final UUID candidatArtisteId = (UUID) candidat.get("artiste_id");
    final boolean memeArtiste =
        concert.artisteId() == null
            || candidatArtisteId == null
            || concert.artisteId().equals(candidatArtisteId);
    final boolean memeJour = concert.date().equals(((Date) candidat.get("date")).toLocalDate());
    return memeArtiste
        && Boolean.TRUE.equals(candidat.get("ancienne"))
        && (memeJour || Boolean.TRUE.equals(candidat.get("seul_produit")));
  }
}
