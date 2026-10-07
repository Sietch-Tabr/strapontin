/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker.sante;

import fr.strapontin.sources.ProduitEcarte;
import fr.strapontin.sources.SourceAvecProduitsEcartes;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Signale les concerts à venir déjà enregistrés sous un produit qu'une
 * récupération par artiste écarte désormais (soirée de festival d'un autre
 * artiste) : sans signalement, ils ne disparaissaient qu'après plusieurs
 * absences et une proposition de suppression.
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
    return signales;
  }

  private int signaler(final ProduitEcarte produit) {
    if (produit.url() == null) {
      return 0;
    }
    final List<UUID> evenements =
        jdbc.queryForList(
            "SELECT DISTINCT e.id FROM observations o JOIN evenements e ON e.id = o.evenement_id"
                + " WHERE o.source = 'eventim' AND o.url = :url AND e.date >= CURRENT_DATE",
            Map.of("url", produit.url()),
            UUID.class);
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
}
