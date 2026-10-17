/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker.persister;

import fr.strapontin.core.Normalisation;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * Renommage d'une salle par sa source de référence (Ticketmaster si mappée,
 * sinon toute source) ; plusieurs mappings de cette source, ou une simple
 * variante de graphie du nom actuel, ne la renomment que pour un nom
 * strictement meilleur, jamais en alternance à chaque cycle.
 * @since 3.0.2
 * @author Kwaite
 */
final class AbsorptionRenommagesSalles {

  private static final Logger LOG = LoggerFactory.getLogger(AbsorptionRenommagesSalles.class);
  private static final int MAPPING_UNIQUE = 1;

  private final NamedParameterJdbcTemplate jdbc;

  /* package-private : instancié par ReconciliationNomsSalles. */
  AbsorptionRenommagesSalles(final NamedParameterJdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * Renomme les salles du lot depuis leur source de référence ; un mapping
   * incohérent géographiquement ne renomme jamais la salle qu'il désigne.
   * @param cachesSalles map sourceId → UUID
   * @param deptsConnus départements déjà connus par salle
   */
  /* package-private : appelé par ReconciliationNomsSalles. */
  void absorber(
      final List<ValeurConcert> concerts,
      final Map<String, UUID> cachesSalles,
      final Map<UUID, String> deptsConnus) {
    final Map<UUID, List<ValeurConcert>> candidatsParSalle = new HashMap<>();
    for (final ValeurConcert c : concerts) {
      if (c.salleSourceId() == null) {
        continue;
      }
      final UUID salleId = cachesSalles.get(c.salleSourceId());
      if (salleId == null
          || PersisterConcerts.mappingSalleIncoherent(salleId, c.dept(), deptsConnus)) {
        continue;
      }
      candidatsParSalle.computeIfAbsent(salleId, _ -> new ArrayList<>()).add(c);
    }
    if (candidatsParSalle.isEmpty()) {
      return;
    }
    final Map<UUID, EtatSalle> etats = chargerEtats(candidatsParSalle.keySet());
    for (final Map.Entry<UUID, List<ValeurConcert>> entree : candidatsParSalle.entrySet()) {
      final EtatSalle etat = etats.get(entree.getKey());
      if (etat == null) {
        continue;
      }
      final String nouveauNom = choisirNom(etat, entree.getValue());
      if (nouveauNom != null) {
        renommer(entree.getKey(), nouveauNom);
      }
    }
  }

  private record EtatSalle(String nom, int nbMappingsTm, int nbMappings) {}

  private Map<UUID, EtatSalle> chargerEtats(final Set<UUID> salleIds) {
    final Map<UUID, EtatSalle> etats = new HashMap<>();
    jdbc.query(
        """
        SELECT s.id, s.nom,
               count(ss.*) FILTER (WHERE ss.source = 'ticketmaster') AS nb_tm,
               count(ss.*) AS nb
        FROM salles s LEFT JOIN salle_sources ss ON ss.salle_id = s.id
        WHERE s.id IN (:ids)
        GROUP BY s.id, s.nom
        """,
        Map.of("ids", List.copyOf(salleIds)),
        rs -> {
          etats.put(
              rs.getObject("id", UUID.class),
              new EtatSalle(rs.getString("nom"), rs.getInt("nb_tm"), rs.getInt("nb")));
        });
    return etats;
  }

  // null : aucun renommage.
  private static String choisirNom(final EtatSalle etat, final List<ValeurConcert> candidats) {
    final boolean referenceTm = etat.nbMappingsTm() > 0;
    final List<String> noms =
        candidats.stream()
            .filter(c -> !referenceTm || "ticketmaster".equals(c.source()))
            .map(ValeurConcert::sallePubliee)
            .distinct()
            .toList();
    if (noms.isEmpty()) {
      return null;
    }
    final String meilleur =
        noms.stream()
            .max(
                Comparator.comparingInt(ReconciliationNomsSalles::scoreSalle)
                    .thenComparing(Comparator.<String>reverseOrder()))
            .orElseThrow();
    if (meilleur.equals(etat.nom())) {
      return null;
    }
    final int mappingsReference = referenceTm ? etat.nbMappingsTm() : etat.nbMappings();
    if (mappingsReference <= MAPPING_UNIQUE && !estVarianteDeNom(meilleur, etat.nom())) {
      return meilleur;
    }
    final boolean strictementMeilleur =
        etat.nom() == null
            || ReconciliationNomsSalles.scoreSalle(meilleur)
                > ReconciliationNomsSalles.scoreSalle(etat.nom());
    return strictementMeilleur ? meilleur : null;
  }

  // Une source publie parfois deux graphies d'un même identifiant selon la requête : seule une
  // graphie strictement meilleure remplace l'actuelle, sinon la salle alterne à chaque lot.
  private static boolean estVarianteDeNom(final String candidat, final String nomActuel) {
    return nomActuel != null
        && Normalisation.normaliserTexte(candidat).equals(Normalisation.normaliserTexte(nomActuel));
  }

  private void renommer(final UUID salleId, final String nouveauNom) {
    final int modifies =
        jdbc.update(
            "UPDATE salles SET nom = :nom, nom_norm = :nomNorm"
                + " WHERE id = CAST(:uuid AS uuid) AND nom <> :nom",
            Map.of(
                "nom",
                nouveauNom,
                "nomNorm",
                Normalisation.normaliserTexte(nouveauNom),
                "uuid",
                salleId.toString()));
    if (modifies > 0) {
      LOG.info("renommage salle absorbé salleId={} nouveauNom={}", salleId, nouveauNom);
    }
  }
}
