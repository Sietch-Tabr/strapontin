/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.concert;

import java.util.List;
import org.springframework.web.bind.annotation.BindParam;

/**
 * Critères de filtrage communs aux listings de concerts ({@code
 * SpectacleService}/{@code DecouverteService}) — lié en {@code
 * @ModelAttribute}, {@code @BindParam} relie aux noms HTTP singuliers
 * ({@code source}/{@code dept}), le constructeur compact restitue les défauts.
 * @since 2.5.4
 * @author Kwaite
 */
public record FiltreConcerts(
    @BindParam("q") String recherche,
    String mois,
    String genre,
    String classification,
    String tri,
    int page,
    boolean quinzaine,
    boolean masques,
    @BindParam("source") List<String> sources,
    String indisponibles,
    @BindParam("dept") List<String> depts) {

  // Restitue les valeurs par défaut qu'un paramètre de requête absent ne
  // porte pas.
  public FiltreConcerts {
    recherche = recherche != null ? recherche : "";
    mois = mois != null ? mois : "";
    genre = genre != null ? genre : "";
    classification = classification != null ? classification : "";
    tri = tri != null ? tri : "date";
  }

  /**
   * Crée un filtre minimal avec les valeurs par défaut.
   *
   * @return filtre sans aucun critère actif
   */
  public static FiltreConcerts vide() {
    return new FiltreConcerts("", "", "", "", "date", 0, false, false, null, null, null);
  }
}
