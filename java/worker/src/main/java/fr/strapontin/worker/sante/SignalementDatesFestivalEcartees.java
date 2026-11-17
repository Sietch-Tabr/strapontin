/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker.sante;

import fr.strapontin.core.Normalisation;
import fr.strapontin.sources.eventim.ProduitEcarte;
import fr.strapontin.sources.eventim.SourceAvecProduitsEcartes;
import fr.strapontin.worker.fetch.WorkerFetchCommun;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Signale les concerts à venir déjà enregistrés sous un produit qu'une
 * récupération par artiste écarte désormais (soirée de festival d'un autre
 * artiste) ; ils gagnent aussi une absence, le produit n'étant plus jamais
 * observé.
 * @since 3.0.3
 * @author Kwaite
 */
@Component
public class SignalementDatesFestivalEcartees {

  /** Type de signalement publié. */
  public static final String TYPE = "date_festival_ecartee";

  private final List<SourceAvecProduitsEcartes> sources;
  private final NamedParameterJdbcTemplate jdbc;
  private final SignalementMatchingService signalements;

  /**
   * @param sources sources qui remontent les produits écartés de leurs récupérations
   * @param jdbc accès JDBC
   * @param signalements publication des signalements
   */
  public SignalementDatesFestivalEcartees(
      final List<SourceAvecProduitsEcartes> sources,
      final NamedParameterJdbcTemplate jdbc,
      final SignalementMatchingService signalements) {
    this.sources = sources;
    this.jdbc = jdbc;
    this.signalements = signalements;
  }

  /**
   * Prélève les produits écartés de chaque source et signale les concerts
   * enregistrés sous l'un d'eux.
   *
   * @return nombre de concerts signalés
   */
  public int signaler() {
    int signales = 0;
    for (final SourceAvecProduitsEcartes source : sources) {
      for (final ProduitEcarte produit : source.preleverProduitsEcartes()) {
        signales += signaler(produit);
      }
    }
    fermerObsoletes();
    return signales;
  }

  // Le concert n'existe plus, est passé, ou a gagné une autre billetterie : la
  // date n'est plus attribuée à tort par ce seul produit.
  private void fermerObsoletes() {
    jdbc.update(
        """
        UPDATE signalements_matching sm SET resolu_le = now()
        WHERE sm.type = :type AND sm.resolu_le IS NULL
          AND NOT EXISTS (
            SELECT 1 FROM evenements e JOIN observations o ON o.evenement_id = e.id
            WHERE e.id = sm.entite_id AND e.date >= CURRENT_DATE
              AND o.source = 'eventim' AND o.url = sm.details ->> 'url'
              AND NOT EXISTS (SELECT 1 FROM observations autre
                              WHERE autre.evenement_id = e.id AND autre.url <> o.url))
        """,
        Map.of("type", TYPE));
  }

  // Attribué à tort seulement s'il n'existe que par ce produit (un billet
  // couplé double le billet normal) et sans autre artiste identifié (le
  // produit écarté pour l'un est la date légitime de l'autre).
  private int signaler(final ProduitEcarte produit) {
    if (produit.url() == null) {
      return 0;
    }
    final List<UUID> evenements =
        jdbc.queryForList(
            """
            SELECT DISTINCT e.id FROM observations o JOIN evenements e ON e.id = o.evenement_id
            WHERE o.source = 'eventim' AND o.url = :url AND e.date >= CURRENT_DATE
              AND NOT EXISTS (SELECT 1 FROM observations autre
                              WHERE autre.evenement_id = e.id AND autre.url <> :url)
              AND (e.artiste_id IS NULL OR e.artiste_norm = :artisteNorm)
            """,
            Map.of(
                "url",
                produit.url(),
                "artisteNorm",
                Normalisation.normaliserTexte(produit.artiste() != null ? produit.artiste() : "")),
            UUID.class);
    compterAbsence(evenements);
    for (final UUID evenement : evenements) {
      signalements.signaler(
          TYPE,
          "evenement",
          evenement,
          "moyenne",
          Map.of(
              "artiste", produit.artiste(),
              "titre", produit.titre() != null ? produit.titre() : "",
              "url", produit.url()));
    }
    return evenements.size();
  }

  // Écarté à chaque récupération, le produit n'est plus jamais observé : le
  // concert qui n'existe que par lui gagne une absence, comme tout disparu.
  private void compterAbsence(final List<UUID> evenements) {
    if (evenements.isEmpty()) {
      return;
    }
    jdbc.update(
        """
        UPDATE evenements SET absences_consecutives = absences_consecutives + 1,
               absence_detectee_le = CASE WHEN absences_consecutives + 1 >= :seuil
                                          THEN COALESCE(absence_detectee_le, now())
                                          ELSE absence_detectee_le END
        WHERE id IN (:ids)
        """,
        Map.of("seuil", WorkerFetchCommun.SEUIL_ABSENCES_CONSECUTIVES, "ids", evenements));
  }
}
