/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker.persister;

import fr.strapontin.core.ProduitEventim;
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

  private final NamedParameterJdbcTemplate jdbc;
  private final FusionEvenementsArtisteService fusionEvenements;

  /**
   * @param jdbc accès JDBC
   * @param fusionEvenements fusion d'une paire d'événements
   */
  public FusionProduitsEventimService(
      final NamedParameterJdbcTemplate jdbc,
      final FusionEvenementsArtisteService fusionEvenements) {
    this.jdbc = jdbc;
    this.fusionEvenements = fusionEvenements;
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
            Map.of("ecartSecondes", ECART_MAX_HEURE_SECONDES));
    int fusions = 0;
    for (final Map<String, Object> paire : paires) {
      final UUID conservee =
          fusionEvenements.fusionnerPaireChoisie(
              (UUID) paire.get("evt_a"), (UUID) paire.get("evt_b"), "même produit Eventim");
      if (conservee != null) {
        retirerObservationsPerimees(conservee);
        fusions++;
      }
    }
    return fusions;
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
}
