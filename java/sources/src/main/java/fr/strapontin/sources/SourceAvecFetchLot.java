/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.sources;

import fr.strapontin.core.ConcertBrut;
import java.util.List;

/// Extension de {@link SourceConcerts} pour les sources supportant le fetch groupé d'artistes.
///
/// <p>Le fetch par lot réduit la consommation de quota : Ticketmaster accepte jusqu'à 20
/// {@code attractionId} par requête, ce qui divise la consommation par 20 par rapport au
/// fetch artiste-par-artiste.
///
/// @since 2.3.1
/// @author Kwaite
public interface SourceAvecFetchLot extends SourceConcerts {

  /// Récupère les concerts de plusieurs artistes en une seule requête.
  ///
  /// @param identifiantsSources identifiants source des artistes (attractionId TM, etc.)
  /// @return concerts trouvés pour l'ensemble du lot
  List<ConcertBrut> fetchArtistesLot(List<String> identifiantsSources);
}
