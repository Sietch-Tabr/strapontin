/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.sources;

import fr.strapontin.core.ConcertBrut;
import java.util.List;

/**
 * Extension de {@link SourceConcerts} pour les sources supportant le
 * fetch groupé d'artistes — TM accepte jusqu'à 20 {@code attractionId}
 * par requête, divisant la consommation de quota par 20.
 * @since 2.3.1
 * @author Kwaite
 */
public interface SourceAvecFetchLot extends SourceConcerts {

  /**
   * Récupère les concerts de plusieurs artistes en une seule requête.
   *
   * @param identifiantsSources identifiants source des artistes (attractionId
   *     TM, etc.)
   * @return concerts trouvés pour l'ensemble du lot
   * @throws ReponseIncompleteException si une page a échoué, avec les concerts reçus
   */
  List<ConcertBrut> fetchArtistesLot(List<String> identifiantsSources);
}
