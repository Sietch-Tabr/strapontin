/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker.persister;

import fr.strapontin.sources.ClientNominatim;
import fr.strapontin.sources.PositionDepartement;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * Dernier recours quand aucune salle n'est reconnue : création à la position
 * publiée par la source, sinon géocodage « nom, ville » (point retenu s'il est
 * dans le département du concert) puis salle connue la plus proche, sinon
 * création aux coordonnées géocodées.
 * @since 3.0.2
 * @author Kwaite
 */
final class CreationSalleDernierRecours {

  private final ClientNominatim clientNominatim;
  private final ResolveurSalleParProximiteGps resolveurGps;
  private final CreateurSalle createur;

  /* package-private : instancié par ResolveurSalleParConcert. */
  CreationSalleDernierRecours(
      final NamedParameterJdbcTemplate jdbc,
      final ClientNominatim clientNominatim,
      final Function<Set<UUID>, Map<UUID, String>> deptsConnus) {
    this.clientNominatim = clientNominatim;
    this.resolveurGps = new ResolveurSalleParProximiteGps(jdbc);
    this.createur = new CreateurSalle(jdbc, deptsConnus);
  }

  /**
   * @param positionPubliee true si la source publie la position du lieu : la
   *     salle est créée sans géocoder son nom, la position y sera enregistrée
   * @return salle retrouvée ou créée
   */
  /* package-private : appelé par ResolveurSalleParConcert. */
  UUID resoudre(final ValeurConcert concert, final boolean positionPubliee) {
    if (positionPubliee) {
      return createur.creer(concert, Optional.empty());
    }
    // Sans la ville, un nom générique se géocode n'importe où en France.
    // Un point hors du département du concert est un homonyme : écarté.
    final Optional<ClientNominatim.Coordonnees> coordonnees =
        clientNominatim
            .geocoder(
                concert.ville() != null
                    ? concert.salle() + ", " + concert.ville()
                    : concert.salle())
            .filter(
                position ->
                    PositionDepartement.plausible(
                        concert.dept(), position.latitude(), position.longitude()));
    final UUID proche =
        coordonnees
            .map(position -> resolveurGps.chercher(position, concert.salle(), concert.dept()))
            .orElse(null);
    return proche != null ? proche : createur.creer(concert, coordonnees);
  }
}
