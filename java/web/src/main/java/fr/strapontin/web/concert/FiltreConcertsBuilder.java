/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.concert;

import java.time.LocalDate;
import java.util.Map;

/// Fragments SQL communs aux requêtes de listing de concerts.
///
/// <p>Mutualise la construction des clauses {@code WHERE}/{@code ORDER BY} partagées entre
/// {@code DecouverteService} (catalogue global) et {@code SpectacleService} (matches utilisateur) :
/// filtres mois/genre/classification, tri et bornes de pagination. Les filtres propres à chaque
/// source (départements, masquage, sources suivies) restent dans leur service respectif.
///
/// <p>Chaque méthode {@code appliquer*} ajoute sa condition au {@link StringBuilder} et ses
/// paramètres nommés à la {@link Map} fournie, sans rien faire si le filtre est absent.
///
/// @since 2.2.3
/// @author Kwaite
public final class FiltreConcertsBuilder {

  /// Nombre de concerts par page pour les listings paginés.
  public static final int CONCERTS_PAR_PAGE = 100;

  private FiltreConcertsBuilder() {
    // Classe utilitaire — pas d'instanciation
  }

  /// Retourne la clause {@code ORDER BY} selon le critère de tri demandé.
  ///
  /// @param tri {@code "nouveaute"} (par date de première détection) ou {@code "date"} (défaut)
  /// @return la clause ORDER BY (sans le mot-clé)
  public static String clauseTri(String tri) {
    return "nouveaute".equals(tri) ? "c.first_seen DESC, c.date ASC" : "c.date ASC, c.artiste ASC";
  }

  /// Tronque une saisie utilisateur après suppression des espaces de bordure.
  ///
  /// @param valeur       la saisie brute
  /// @param maxLongueur  longueur maximale conservée
  /// @return la saisie nettoyée et tronquée
  public static String tronquer(String valeur, int maxLongueur) {
    String nettoyee = valeur.strip();
    return nettoyee.substring(0, Math.min(nettoyee.length(), maxLongueur));
  }

  /// Ajoute le filtre mois ({@code YYYY-MM}) ; sans effet si le format est invalide.
  ///
  /// @param where  clause WHERE en construction
  /// @param params paramètres nommés
  /// @param mois   mois au format {@code YYYY-MM}
  public static void appliquerMois(StringBuilder where, Map<String, Object> params, String mois) {
    if (!mois.isBlank() && mois.matches("\\d{4}-\\d{2}")) {
      LocalDate debutMois = LocalDate.parse(mois + "-01");
      where.append(" AND c.date >= :moisDebut AND c.date < :moisFin");
      params.put("moisDebut", debutMois);
      params.put("moisFin", debutMois.plusMonths(1));
    }
  }

  /// Ajoute le filtre genre ({@code ILIKE}) ; sans effet si le genre est vide.
  ///
  /// @param where    clause WHERE en construction
  /// @param params   paramètres nommés
  /// @param genreStr genre déjà tronqué via {@link #tronquer(String, int)}
  public static void appliquerGenre(
      StringBuilder where, Map<String, Object> params, String genreStr) {
    if (!genreStr.isEmpty()) {
      where.append(" AND c.genre ILIKE :genre");
      params.put("genre", genreStr);
    }
  }

  /// Ajoute le filtre classification (égalité) ; sans effet si la classification est vide.
  ///
  /// @param where          clause WHERE en construction
  /// @param params         paramètres nommés
  /// @param classification classification exacte
  public static void appliquerClassification(
      StringBuilder where, Map<String, Object> params, String classification) {
    if (!classification.isBlank()) {
      where.append(" AND c.classification = :classification");
      params.put("classification", classification);
    }
  }
}
