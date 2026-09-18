/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.concert;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Map;

/**
 * Fragments SQL communs aux requêtes de listing de concerts — mutualise
 * {@code WHERE}/{@code ORDER BY} entre {@code DecouverteService} et {@code
 * SpectacleService}. Chaque méthode {@code appliquer*} ajoute sa condition
 * et ses paramètres, sans effet si le filtre est absent.
 * @since 2.2.3
 * @author Kwaite
 */
public final class FiltreConcertsBuilder {

  /** Nombre de concerts par page pour les listings paginés. */
  public static final int CONCERTS_PAR_PAGE = 100;

  private FiltreConcertsBuilder() {
    // Classe utilitaire — pas d'instanciation
  }

  /**
   * Retourne la clause {@code ORDER BY} selon le critère de tri demandé.
   *
   * @param tri {@code "nouveaute"} (par date de première détection) ou
   *     {@code "date"} (défaut)
   * @return la clause ORDER BY (sans le mot-clé)
   */
  public static String clauseTri(final String tri) {
    return "nouveaute".equals(tri) ? "c.first_seen DESC, c.date ASC" : "c.date ASC, c.artiste ASC";
  }

  /**
   * Tronque une saisie utilisateur après suppression des espaces de bordure.
   *
   * @param valeur la saisie brute
   * @param maxLongueur longueur maximale conservée
   * @return la saisie nettoyée et tronquée
   */
  public static String tronquer(final String valeur, final int maxLongueur) {
    final String nettoyee = valeur.strip();
    return nettoyee.substring(0, Math.min(nettoyee.length(), maxLongueur));
  }

  /**
   * Ajoute le filtre mois ({@code YYYY-MM}), sans effet si invalide — la
   * regex ne valide que la forme, pas la validité calendaire ({@code
   * 2026-13}), d'où le {@code try/catch} sur {@link LocalDate#parse}.
   * @param where clause WHERE en construction
   * @param params paramètres nommés
   * @param mois mois au format {@code YYYY-MM}
   */
  public static void appliquerMois(
      final StringBuilder where, final Map<String, Object> params, final String mois) {
    if (mois.isBlank() || !mois.matches("\\d{4}-\\d{2}")) {
      return;
    }
    final LocalDate debutMois;
    try {
      debutMois = LocalDate.parse(mois + "-01");
    } catch (DateTimeParseException e) {
      return;
    }
    where.append(" AND c.date >= :moisDebut AND c.date < :moisFin");
    params.put("moisDebut", debutMois);
    params.put("moisFin", debutMois.plusMonths(1));
  }

  /**
   * Ajoute le filtre genre ({@code ILIKE}) ; sans effet si le genre est vide.
   *
   * @param where clause WHERE en construction
   * @param params paramètres nommés
   * @param genreStr genre déjà tronqué via {@link #tronquer(String, int)}
   */
  public static void appliquerGenre(
      final StringBuilder where, final Map<String, Object> params, final String genreStr) {
    if (!genreStr.isEmpty()) {
      where.append(" AND c.genre ILIKE :genre");
      params.put("genre", genreStr);
    }
  }

  /**
   * Ajoute le filtre classification (égalité) ; sans effet si la classification
   * est vide.
   *
   * @param where clause WHERE en construction
   * @param params paramètres nommés
   * @param classification classification exacte
   */
  public static void appliquerClassification(
      final StringBuilder where, final Map<String, Object> params, final String classification) {
    if (!classification.isBlank()) {
      where.append(" AND c.classification = :classification");
      params.put("classification", classification);
    }
  }
}
