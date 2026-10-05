/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.sources;

import fr.strapontin.core.ConcertBrut;
import java.io.Serial;
import java.time.LocalDate;
import java.util.List;

/**
 * Récupération interrompue par une page en échec (quota refusé, erreur HTTP) :
 * les concerts reçus restent exploitables, mais un concert attendu et non
 * reçu n'est pas absent pour autant.
 * @since 3.0.2
 * @author Kwaite
 */
public class ReponseIncompleteException extends RuntimeException {

  @Serial private static final long serialVersionUID = 1L;

  private final transient List<ConcertBrut> recus;
  private final LocalDate couverture;

  /**
   * @param message description de la requête en échec
   * @param concertsRecus concerts reçus avant l'échec
   * @param couvertJusquau dernière date entièrement couverte, null si inconnue
   */
  public ReponseIncompleteException(
      final String message, final List<ConcertBrut> concertsRecus, final LocalDate couvertJusquau) {
    super(message);
    this.recus = List.copyOf(concertsRecus);
    this.couverture = couvertJusquau;
  }

  /**
   * @return concerts reçus avant l'échec
   */
  public List<ConcertBrut> concertsRecus() {
    return recus;
  }

  /**
   * @return dernière date entièrement couverte, null si inconnue
   */
  public LocalDate couvertJusquau() {
    return couverture;
  }
}
