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
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Aligne la ville d'une salle sans élément Wikidata sur la commune de sa
 * position (« Reims » pour Le K, à Tinqueux) par un géocodage inverse, quelques
 * salles par nuit ; même département seulement. Une salle sans ville, avec ou
 * sans élément Wikidata, reçoit de même la commune de sa position.
 * @since 3.1.2
 * @author Kwaite
 */
@Service
public class AlignementCommuneSalles {

  private static final Logger LOG = LoggerFactory.getLogger(AlignementCommuneSalles.class);
  private static final int SALLES_PAR_NUIT = 20;
  private static final String CLE_VILLE = "ville";
  private static final String TYPE_SIGNALEMENT = "ville_salle_divergente";
  private static final int JOURS_ENTRE_VERIFICATIONS = 90;

  private final NamedParameterJdbcTemplate jdbc;
  private final ClientNominatim nominatim;
  private final LogsAdminWorker logsAdminWorker;
  private final SignalementMatchingService signalements;

  /**
   * @param jdbc accès JDBC
   * @param nominatim géocodage inverse
   * @param logsAdminWorker trace de chaque ville corrigée
   * @param signalements signalement d'une ville que les concerts contredisent
   */
  public AlignementCommuneSalles(
      final NamedParameterJdbcTemplate jdbc,
      final ClientNominatim nominatim,
      final LogsAdminWorker logsAdminWorker,
      final SignalementMatchingService signalements) {
    this.jdbc = jdbc;
    this.nominatim = nominatim;
    this.logsAdminWorker = logsAdminWorker;
    this.signalements = signalements;
  }

  /**
   * Renseigne la ville des salles qui n'en ont pas, puis vérifie un lot de
   * salles et corrige la ville de celles dont la position tombe dans une autre
   * commune du même département.
   * @return nombre de villes renseignées ou corrigées
   */
  public int aligner() {
    return completerVillesManquantes() + corrigerVillesDivergentes();
  }

  private int completerVillesManquantes() {
    final List<Map<String, Object>> salles =
        jdbc.queryForList(
            """
            SELECT s.id, s.nom, s.dept, s.latitude, s.longitude
            FROM salles s
            WHERE s.ville IS NULL AND s.dept IS NOT NULL
              AND s.latitude IS NOT NULL AND s.longitude IS NOT NULL
              AND (s.commune_verifiee_le IS NULL
                   OR s.commune_verifiee_le < now() - make_interval(days => :jours))
            ORDER BY s.commune_verifiee_le NULLS FIRST
            LIMIT :nombre
            """,
            Map.of("jours", JOURS_ENTRE_VERIFICATIONS, "nombre", SALLES_PAR_NUIT));
    int renseignees = 0;
    for (final Map<String, Object> salle : salles) {
      final ClientNominatim.ResultatCommune resultat = interroger(salle);
      if (!resultat.succes()) {
        LOG.warn("Nominatim injoignable, villes manquantes interrompues");
        break;
      }
      final Optional<String> commune = resultat.commune().map(NomVille::nettoyer);
      if (commune.isPresent() && memeDepartement(resultat, (String) salle.get("dept"))) {
        jdbc.update(
            "UPDATE salles SET ville = :ville WHERE id = :id AND ville IS NULL",
            Map.of(CLE_VILLE, commune.get(), "id", salle.get("id")));
        logsAdminWorker.inserer(
            "info",
            "reconciliation-nocturne",
            "Ville de la salle « "
                + salle.get("nom")
                + " » renseignée d'après sa position : "
                + commune.get(),
            Map.of("salleId", ((UUID) salle.get("id")).toString(), CLE_VILLE, commune.get()));
        renseignees++;
      }
      jdbc.update(
          "UPDATE salles SET commune_verifiee_le = now() WHERE id = :id",
          Map.of("id", salle.get("id")));
    }
    return renseignees;
  }

  private ClientNominatim.ResultatCommune interroger(final Map<String, Object> salle) {
    return nominatim.communeDe(
        new ClientNominatim.Coordonnees(
            ((Number) salle.get("latitude")).doubleValue(),
            ((Number) salle.get("longitude")).doubleValue()));
  }

  private static boolean memeDepartement(
      final ClientNominatim.ResultatCommune resultat, final String dept) {
    return resultat
        .codePostal()
        .map(Normalisation::extraireDeptFrancais)
        .or(resultat::departement)
        .stream()
        .anyMatch(dept::equals);
  }

  private int corrigerVillesDivergentes() {
    final List<Map<String, Object>> salles =
        jdbc.queryForList(
            """
            SELECT s.id, s.nom, s.ville, s.dept, s.latitude, s.longitude, s.code_postal
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
      final ClientNominatim.ResultatCommune resultat = interroger(salle);
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

  private Set<String> villesDesConcerts(final UUID salleId) {
    return jdbc
        .queryForList(
            "SELECT DISTINCT ville FROM evenements WHERE salle_id = :id AND ville IS NOT NULL",
            Map.of("id", salleId),
            String.class)
        .stream()
        .map(Normalisation::normaliserTexte)
        .collect(Collectors.toSet());
  }

  private static boolean codePostalConfirme(
      final Map<String, Object> salle, final ClientNominatim.ResultatCommune resultat) {
    final Object codePostalSalle = salle.get("code_postal");
    return codePostalSalle != null
        && resultat.codePostal().filter(codePostalSalle.toString()::equals).isPresent();
  }

  private boolean corrigerSiBesoin(
      final Map<String, Object> salle, final ClientNominatim.ResultatCommune resultat) {
    final Optional<String> commune = resultat.commune().map(NomVille::nettoyer);
    final String ville = (String) salle.get(CLE_VILLE);
    final String dept = (String) salle.get("dept");
    final UUID id = (UUID) salle.get("id");
    if (commune.isEmpty() || !memeDepartement(resultat, dept)) {
      return false;
    }
    final String communeNormalisee = Normalisation.normaliserTexte(commune.get());
    if (communeNormalisee.equals(Normalisation.normaliserTexte(ville))) {
      signalements.cloreEntite(TYPE_SIGNALEMENT, id);
      return false;
    }
    // Une position de billetterie est imprécise près d'une limite communale :
    // si les concerts de la salle nomment sa ville actuelle et jamais la
    // commune calculée, la ville est gardée, sauf si le code postal de la salle
    // confirme la zone de la position.
    final Set<String> villesDesConcerts = villesDesConcerts(id);
    if (!codePostalConfirme(salle, resultat)
        && villesDesConcerts.contains(Normalisation.normaliserTexte(ville))
        && !villesDesConcerts.contains(communeNormalisee)) {
      signalements.signaler(
          TYPE_SIGNALEMENT,
          "salle",
          id,
          "basse",
          Map.of(CLE_VILLE, ville, "communeCalculee", commune.get()));
      return false;
    }
    signalements.cloreEntite(TYPE_SIGNALEMENT, id);
    jdbc.update(
        "UPDATE salles SET ville = :ville WHERE id = :id",
        Map.of(CLE_VILLE, commune.get(), "id", salle.get("id")));
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
