/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker.persister;

import fr.strapontin.worker.LogsAdminWorker;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Résolution par nom d'un concert sans mapping salle_sources exploitable
 * (absent, ou rejeté pour incohérence géographique puis réassigné à la salle
 * cohérente trouvée) — extrait de {@link PersisterConcerts} (taille).
 * @since 3.0.1
 * @author Kwaite
 */
@Service
class RepliMappingSalle {

  private final ReconciliateurSalles reconciliateurSalles;
  private final LogsAdminWorker logsAdminWorker;
  private final NamedParameterJdbcTemplate jdbc;

  /* package-private : instancié par Spring (même package). */
  RepliMappingSalle(
      final ReconciliateurSalles reconciliateurSalles,
      final LogsAdminWorker logsAdminWorker,
      final NamedParameterJdbcTemplate jdbc) {
    this.reconciliateurSalles = reconciliateurSalles;
    this.logsAdminWorker = logsAdminWorker;
    this.jdbc = jdbc;
  }

  // Un mapping salle_sources réutilisé contourne les garde-fous géographiques
  // appliqués à la création d'un nouveau mapping — à valider avant de lui faire
  // confiance.
  /* package-private : appelé par PersisterConcerts (même package). */
  Map<String, UUID> resoudreSallesParNomFallback(
      final List<ValeurConcert> concerts,
      final Map<String, UUID> cachesSalles,
      final Map<UUID, String> deptsConnus) {
    final Map<String, UUID> cachesSallesParNom = new HashMap<>();
    for (final ValeurConcert c : concerts) {
      final UUID salleMappee =
          c.salleSourceId() != null ? cachesSalles.get(c.salleSourceId()) : null;
      final boolean mappingIncoherent =
          PersisterConcerts.mappingSalleIncoherent(salleMappee, c.dept(), deptsConnus);
      if (mappingIncoherent) {
        journaliserMappingIncoherent(salleMappee, c, deptsConnus);
      }
      final boolean besoinFallback = c.salleSourceId() == null || mappingIncoherent;
      if (besoinFallback && !cachesSallesParNom.containsKey(c.salleNorm())) {
        cachesSallesParNom.put(
            c.salleNorm(), resoudreSalleFallback(c, mappingIncoherent ? salleMappee : null));
      }
    }
    return cachesSallesParNom;
  }

  // Mapping salle_sources rejeté sans repli flou disponible — traité comme absent :
  // résolution/création complète plutôt qu'un salle_id indéfiniment null. Le mapping
  // rejeté est ensuite réassigné à la salle cohérente trouvée, pour ne plus être
  // recontourné à chaque cycle.
  private UUID resoudreSalleFallback(final ValeurConcert concert, final UUID salleRejetee) {
    UUID salleId = reconciliateurSalles.chercherSalleParTokens(concert.salle(), concert.dept());
    if (salleId == null) {
      salleId = reconciliateurSalles.chercherSalleParCleForte(concert);
    }
    if (salleId == null && salleRejetee != null) {
      salleId = reconciliateurSalles.upsertSalleSource(concert);
    }
    if (salleRejetee == null || salleId == null) {
      return salleId;
    }
    if (salleId.equals(salleRejetee)) {
      return null;
    }
    if (reassignerMappingSalle(concert.salleSourceId(), salleRejetee, salleId)) {
      logsAdminWorker.inserer(
          "info",
          "persistance",
          "mapping salle_sources réassigné à une salle du bon département source="
              + concert.salleSourceId()
              + " ancienneSalle="
              + salleRejetee
              + " nouvelleSalle="
              + salleId,
          Map.of(
              "salleSourceId", concert.salleSourceId(),
              "ancienneSalle", salleRejetee.toString(),
              "nouvelleSalle", salleId.toString()));
    }
    return salleId;
  }

  private void journaliserMappingIncoherent(
      final UUID salleMappee, final ValeurConcert concert, final Map<UUID, String> deptsConnus) {
    logsAdminWorker.inserer(
        "warn",
        "persistance",
        "mapping salle_sources ignoré — incohérence géographique salleId="
            + salleMappee
            + " deptConnu="
            + deptsConnus.get(salleMappee)
            + " deptEntrant="
            + concert.dept()
            + " artiste="
            + concert.artisteNorm(),
        Map.of("salleId", salleMappee.toString(), "artiste", concert.artisteNorm()));
  }

  // Sans réassignation, le mapping rejeté est recontourné à chaque cycle et
  // l'événement déjà rattaché à la bonne salle n'est plus jamais mis à jour.
  private boolean reassignerMappingSalle(
      final String salleSourceId, final UUID ancienneSalle, final UUID nouvelleSalle) {
    final int sep = salleSourceId.indexOf(':');
    if (sep <= 0 || nouvelleSalle.equals(ancienneSalle)) {
      return false;
    }
    return jdbc.update(
            "UPDATE salle_sources SET salle_id = CAST(:nouvelle AS uuid)"
                + " WHERE source = :source AND identifiant_externe = :identifiant"
                + " AND salle_id = CAST(:ancienne AS uuid)",
            Map.of(
                "nouvelle", nouvelleSalle.toString(),
                "ancienne", ancienneSalle.toString(),
                "source", salleSourceId.substring(0, sep),
                "identifiant", salleSourceId.substring(sep + 1)))
        > 0;
  }
}
