/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker.sante;

import fr.strapontin.core.NomVille;
import fr.strapontin.core.Normalisation;
import fr.strapontin.sources.enrichissement.ClientNominatim;
import fr.strapontin.worker.LogsAdminWorker;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Aligne la ville d'une salle sans élément Wikidata sur la commune où tombe sa
 * position : les billetteries annoncent la grande ville voisine (« Reims » pour
 * Le K, à Tinqueux). Quelques salles par nuit, la plus anciennement vérifiée
 * d'abord, par un géocodage inverse cadencé ; la ville ne change que si la
 * commune trouvée est du même département.
 * @since 3.1.2
 * @author Kwaite
 */
@Service
public class AlignementCommuneSalles {

  private static final Logger LOG = LoggerFactory.getLogger(AlignementCommuneSalles.class);
  private static final int SALLES_PAR_NUIT = 20;
  private static final int JOURS_ENTRE_VERIFICATIONS = 90;

  private final NamedParameterJdbcTemplate jdbc;
  private final ClientNominatim nominatim;
  private final LogsAdminWorker logsAdminWorker;

  /**
   * @param jdbc accès JDBC
   * @param nominatim géocodage inverse
   * @param logsAdminWorker trace de chaque ville corrigée
   */
  public AlignementCommuneSalles(
      final NamedParameterJdbcTemplate jdbc,
      final ClientNominatim nominatim,
      final LogsAdminWorker logsAdminWorker) {
    this.jdbc = jdbc;
    this.nominatim = nominatim;
    this.logsAdminWorker = logsAdminWorker;
  }

  /**
   * Vérifie un lot de salles et corrige la ville de celles dont la position
   * tombe dans une autre commune du même département.
   * @return nombre de villes corrigées
   */
  public int aligner() {
    final List<Map<String, Object>> salles =
        jdbc.queryForList(
            """
            SELECT s.id, s.nom, s.ville, s.dept, s.latitude, s.longitude
            FROM salles s
            WHERE s.wikidata_id IS NULL AND s.origine_position = 'sources'
              AND s.latitude IS NOT NULL AND s.longitude IS NOT NULL
              AND s.ville IS NOT NULL AND s.dept IS NOT NULL
              AND (s.commune_verifiee_le IS NULL
                   OR s.commune_verifiee_le < now() - make_interval(days => :jours))
              AND EXISTS (SELECT 1 FROM evenements e
                          WHERE e.salle_id = s.id AND e.date >= CURRENT_DATE)
            ORDER BY s.commune_verifiee_le NULLS FIRST
            LIMIT :nombre
            """,
            Map.of("jours", JOURS_ENTRE_VERIFICATIONS, "nombre", SALLES_PAR_NUIT));
    int corrigees = 0;
    for (final Map<String, Object> salle : salles) {
      final ClientNominatim.ResultatCommune resultat =
          nominatim.communeDe(
              new ClientNominatim.Coordonnees(
                  ((Number) salle.get("latitude")).doubleValue(),
                  ((Number) salle.get("longitude")).doubleValue()));
      if (!resultat.succes()) {
        LOG.warn("Nominatim injoignable, alignement des communes interrompu");
        break;
      }
      if (corrigerSiBesoin(salle, resultat)) {
        corrigees++;
      }
      jdbc.update(
          "UPDATE salles SET commune_verifiee_le = now() WHERE id = :id",
          Map.of("id", salle.get("id")));
    }
    return corrigees;
  }

  private boolean corrigerSiBesoin(
      final Map<String, Object> salle, final ClientNominatim.ResultatCommune resultat) {
    final Optional<String> commune = resultat.commune().map(NomVille::nettoyer);
    final String ville = (String) salle.get("ville");
    final String dept = (String) salle.get("dept");
    final boolean memeDepartement =
        resultat.codePostal().map(code -> Normalisation.extraireDeptFrancais(code)).stream()
            .anyMatch(dept::equals);
    if (commune.isEmpty()
        || !memeDepartement
        || Normalisation.normaliserTexte(commune.get())
            .equals(Normalisation.normaliserTexte(ville))) {
      return false;
    }
    jdbc.update(
        "UPDATE salles SET ville = :ville WHERE id = :id",
        Map.of("ville", commune.get(), "id", salle.get("id")));
    logsAdminWorker.inserer(
        "info",
        "reconciliation-nocturne",
        "Ville de la salle « "
            + salle.get("nom")
            + " » alignée sur sa commune : "
            + ville
            + " → "
            + commune.get(),
        Map.of("salleId", ((UUID) salle.get("id")).toString(), "ancienne", ville));
    return true;
  }
}
