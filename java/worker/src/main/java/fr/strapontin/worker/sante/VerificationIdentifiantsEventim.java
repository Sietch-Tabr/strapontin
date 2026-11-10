/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker.sante;

import fr.strapontin.sources.eventim.EventimSource;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Vérifie, par lots, que l'identifiant Eventim d'un artiste désigne bien cet
 * artiste : celui d'un autre (« Love and Furies » pour « Furies ») est
 * signalé, jamais supprimé en silence.
 * @since 3.1.1
 * @author Kwaite
 */
@Service
public class VerificationIdentifiantsEventim {

  private static final Logger LOG = LoggerFactory.getLogger(VerificationIdentifiantsEventim.class);
  private static final int TAILLE_LOT = 40;
  private static final String TYPE_SIGNALEMENT = "identifiant_externe_autre_artiste";
  private static final String PREFIXE = "eventim:";

  private final NamedParameterJdbcTemplate jdbc;
  private final EventimSource eventim;
  private final SignalementMatchingService signalements;

  /**
   * @param jdbc accès JDBC
   * @param eventim source Eventim, interrogée par nom d'artiste
   * @param signalements point d'entrée des signalements de matching
   */
  public VerificationIdentifiantsEventim(
      final NamedParameterJdbcTemplate jdbc,
      final EventimSource eventim,
      final SignalementMatchingService signalements) {
    this.jdbc = jdbc;
    this.eventim = eventim;
    this.signalements = signalements;
  }

  /**
   * Vérifie le lot d'identifiants Eventim le plus anciennement vérifié.
   * @return nombre d'identifiants signalés dans ce passage
   */
  public int verifier() {
    final List<Map<String, Object>> lot =
        jdbc.queryForList(
            "SELECT s.artiste_id, s.identifiant_externe, a.nom,"
                + " a.nom_affiche AS alias,"
                + " (a.deezer_id IS NOT NULL OR a.wikidata_id IS NOT NULL) AS identite_confirmee"
                + " FROM artiste_sources s JOIN artistes a ON a.id = s.artiste_id"
                + " WHERE s.source = 'eventim' ORDER BY s.verifie_le NULLS FIRST LIMIT :taille",
            Map.of("taille", TAILLE_LOT));
    int signales = 0;
    for (final Map<String, Object> ligne : lot) {
      final String identifiant = (String) ligne.get("identifiant_externe");
      final UUID artisteId = (UUID) ligne.get("artiste_id");
      final String attraction =
          identifiant.startsWith(PREFIXE) ? identifiant.substring(PREFIXE.length()) : identifiant;
      final boolean autreArtiste;
      try {
        autreArtiste =
            eventim.attractionDUnAutreArtiste(
                (String) ligne.get("nom"),
                attraction,
                Boolean.TRUE.equals(ligne.get("identite_confirmee")),
                alias((String) ligne.get("alias")));
      } catch (RuntimeException e) {
        LOG.warn(
            "Eventim injoignable, vérification des identifiants interrompue : {}", e.getMessage());
        break;
      }
      jdbc.update(
          "UPDATE artiste_sources SET verifie_le = now() WHERE source = 'eventim'"
              + " AND identifiant_externe = :id",
          Map.of("id", identifiant));
      if (autreArtiste) {
        signalements.signaler(
            TYPE_SIGNALEMENT,
            "artiste",
            artisteId,
            "haute",
            Map.of("source", "eventim", "identifiant", identifiant, "artiste", ligne.get("nom")));
        signales++;
      } else {
        signalements.cloreEntite(TYPE_SIGNALEMENT, artisteId);
      }
    }
    return signales;
  }

  private static List<String> alias(final String nomAffiche) {
    return nomAffiche == null ? List.of() : List.of(nomAffiche);
  }
}
