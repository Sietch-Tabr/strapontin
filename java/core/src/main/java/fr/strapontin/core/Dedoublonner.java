/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.core;

import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Dédoublonne des concerts issus de plusieurs sources (TM, Eventim, Digitick) en trois passes de
 * rapprochement progressivement plus tolérantes.
 *
 * @author Kwaite
 */
public final class Dedoublonner {

  private static final double SEUIL_SIMILARITE = 0.85;
  // Poids du score de richesse diacritique dans l'arbitrage du nom de salle retenu par fusionner
  // — favorise une forme accentuée correcte ("Théâtre") sur une casse mixte sans accent.
  private static final int POIDS_DIACRITIQUES = 2;
  private static final String SANS_HEURE = "SANS_HEURE";
  // Tolérance sous laquelle deux heures distinctes restent compatibles pour la fusion, très en
  // dessous de tout écart matinée/soirée réel (toujours de l'ordre de l'heure).
  private static final int TOLERANCE_HEURE_MINUTES = 3;

  private Dedoublonner() {}

  private record ConcertEnrichi(
      ConcertBrut concert, String artisteNorm, String villeNorm, String salleNorm) {}

  private static ConcertEnrichi enrichir(final ConcertBrut c) {
    final String salleNorm = Normalisation.normaliserTexte(c.salle());
    // Ville absente : repli sur salleNorm plutôt qu'une chaîne vide partagée par tous les
    // concerts sans ville — deux concerts du même artiste le même jour, dans deux villes
    // réellement différentes mais toutes deux non renseignées, ne fusionnent alors que s'ils
    // partagent aussi la même salle.
    final String villeNorm =
        c.ville() != null ? Normalisation.normaliserVille(c.ville()) : salleNorm;
    return new ConcertEnrichi(c, Normalisation.normaliserTexte(c.artiste()), villeNorm, salleNorm);
  }

  /**
   * Dédoublonne une liste de concerts en trois passes progressivement plus tolérantes : égalité
   * exacte (artiste/date/heure/ville normalisés), similarité floue de l'artiste à date et ville
   * identiques, puis repli sur la salle pour les concerts dont la ville diverge entre sources.
   *
   * @param concerts les concerts bruts, éventuellement en double entre sources
   * @return les concerts dédoublonnés, chaque doublon fusionné vers sa version la plus complète
   */
  public static List<ConcertBrut> dedoublonner(final List<ConcertBrut> concerts) {
    if (concerts.isEmpty()) {
      return List.of();
    }
    final List<ConcertEnrichi> enrichis = concerts.stream().map(Dedoublonner::enrichir).toList();
    final List<ConcertEnrichi> apresPasseExacte = passeExacte(enrichis);
    final List<ConcertEnrichi> apresPasseFuzzy = passeFuzzyDateVille(apresPasseExacte);
    return passeFallbackDateSalle(apresPasseFuzzy);
  }

  // Passe 1 : exacte — (artiste_norm, date, heure, ville_norm). L'heure est incluse : sans elle,
  // deux concerts réellement distincts (matinée + soirée, même artiste/date/ville) fusionnaient
  // à tort dès cette première passe.
  private static List<ConcertEnrichi> passeExacte(final List<ConcertEnrichi> enrichis) {
    final Map<String, List<ConcertEnrichi>> groupes = new LinkedHashMap<>();
    for (final ConcertEnrichi c : enrichis) {
      final String heureCle = c.concert().heure() != null ? c.concert().heure() : SANS_HEURE;
      final String cle =
          c.artisteNorm() + "|" + c.concert().date() + "|" + heureCle + "|" + c.villeNorm();
      groupes.computeIfAbsent(cle, _ -> new ArrayList<>()).add(c);
    }
    return groupes.values().stream().map(Dedoublonner::fusionner).toList();
  }

  // Passe 2 : fuzzy — (date, ville_norm) + similarité artiste >= seuil.
  private static List<ConcertEnrichi> passeFuzzyDateVille(
      final List<ConcertEnrichi> apresPasseExacte) {
    final Map<String, List<ConcertEnrichi>> groupes = new LinkedHashMap<>();
    for (final ConcertEnrichi c : apresPasseExacte) {
      final String cle = c.concert().date() + "|" + c.villeNorm();
      groupes.computeIfAbsent(cle, _ -> new ArrayList<>()).add(c);
    }
    final List<ConcertEnrichi> resultat = new ArrayList<>();
    for (final List<ConcertEnrichi> groupe : groupes.values()) {
      if (groupe.size() <= 1) {
        resultat.addAll(groupe);
      } else {
        resultat.addAll(grouperParSimilarite(groupe));
      }
    }
    return resultat;
  }

  // Fallback : (date, salle_norm) pour les grandes salles avec villes divergentes entre sources.
  private static List<ConcertBrut> passeFallbackDateSalle(
      final List<ConcertEnrichi> apresPasseFuzzy) {
    final Map<String, List<ConcertEnrichi>> groupes = new LinkedHashMap<>();
    for (final ConcertEnrichi c : apresPasseFuzzy) {
      final String cle = c.concert().date() + "|" + c.salleNorm();
      groupes.computeIfAbsent(cle, _ -> new ArrayList<>()).add(c);
    }
    final List<ConcertBrut> resultat = new ArrayList<>();
    for (final List<ConcertEnrichi> groupe : groupes.values()) {
      if (groupe.size() <= 1) {
        resultat.add(groupe.get(0).concert());
      } else {
        grouperParSimilarite(groupe).forEach(c -> resultat.add(c.concert()));
      }
    }
    return resultat;
  }

  private static List<ConcertEnrichi> grouperParSimilarite(final List<ConcertEnrichi> groupe) {
    final List<ConcertEnrichi> fusionnes = new ArrayList<>();
    final boolean[] traites = new boolean[groupe.size()];

    for (int i = 0; i < groupe.size(); i++) {
      if (traites[i]) {
        continue;
      }
      final List<ConcertEnrichi> sousGroupe = new ArrayList<>();
      sousGroupe.add(groupe.get(i));
      traites[i] = true;

      for (int j = i + 1; j < groupe.size(); j++) {
        if (traites[j]) {
          continue;
        }
        final double sim =
            DistanceTexte.similarite(groupe.get(i).artisteNorm(), groupe.get(j).artisteNorm());
        if (sim >= SEUIL_SIMILARITE && heuresCompatibles(groupe.get(i), groupe.get(j))) {
          sousGroupe.add(groupe.get(j));
          traites[j] = true;
        }
      }
      fusionnes.add(fusionner(sousGroupe));
    }
    return fusionnes;
  }

  /**
   * Indique si deux concerts peuvent fusionner du point de vue de l'heure.
   *
   * <p>Empêche la passe 2 (fuzzy, groupée par date+ville sans heure) de refusionner deux concerts
   * que la passe 1 vient de distinguer par heure (matinée + soirée) : deux heures renseignées et
   * distantes de plus de {@value #TOLERANCE_HEURE_MINUTES} minutes signent deux concerts distincts,
   * une heure absente d'un côté (ou des deux) ne permet ni de confirmer ni d'exclure la fusion.
   *
   * @param a premier concert enrichi
   * @param b second concert enrichi
   * @return {@code true} si les heures ne s'opposent pas à une fusion
   */
  private static boolean heuresCompatibles(final ConcertEnrichi a, final ConcertEnrichi b) {
    final String heureA = a.concert().heure();
    final String heureB = b.concert().heure();
    if (heureA == null || heureB == null || heureA.equals(heureB)) {
      return true;
    }
    final Integer minutesA = minutesDepuisMinuit(heureA);
    final Integer minutesB = minutesDepuisMinuit(heureB);
    if (minutesA == null || minutesB == null) {
      return false;
    }
    return Math.abs(minutesA - minutesB) <= TOLERANCE_HEURE_MINUTES;
  }

  private static Integer minutesDepuisMinuit(final String heure) {
    try {
      final LocalTime lt = LocalTime.parse(heure);
      return lt.getHour() * 60 + lt.getMinute();
    } catch (DateTimeParseException e) {
      return null;
    }
  }

  private static ConcertEnrichi choisirMeilleur(
      final ConcertEnrichi meilleur, final ConcertEnrichi candidat) {
    final int scoreM = scoreCompletude(meilleur.concert());
    final int scoreC = scoreCompletude(candidat.concert());
    if (scoreC > scoreM) {
      return candidat;
    }
    if (scoreC == scoreM) {
      return scoreNomRiche(candidat.concert().artiste())
              > scoreNomRiche(meilleur.concert().artiste())
          ? candidat
          : meilleur;
    }
    return meilleur;
  }

  // equalsIgnoreCase ne convient pas ici : le test porte sur chaque nom lui-même (déjà entièrement
  // majuscule ou non), pas sur une comparaison insensible à la casse entre deux chaînes distinctes.
  private static ConcertEnrichi fusionner(final List<ConcertEnrichi> concerts) {
    final ConcertEnrichi gagnant =
        concerts.stream().reduce(Dedoublonner::choisirMeilleur).orElseThrow();

    String meilleureSalle = gagnant.concert().salle();
    for (final ConcertEnrichi c : concerts) {
      final int scoreM =
          scoreNomRiche(meilleureSalle) * POIDS_DIACRITIQUES
              + (meilleureSalle.equals(meilleureSalle.toUpperCase(Locale.ROOT)) ? 0 : 1);
      final int scoreC =
          scoreNomRiche(c.concert().salle()) * POIDS_DIACRITIQUES
              + (c.concert().salle().equals(c.concert().salle().toUpperCase(Locale.ROOT)) ? 0 : 1);
      if (scoreC > scoreM) {
        meilleureSalle = c.concert().salle();
      }
    }

    String meilleurArtiste = gagnant.concert().artiste();
    for (final ConcertEnrichi c : concerts) {
      if (scoreNomRiche(c.concert().artiste()) > scoreNomRiche(meilleurArtiste)) {
        meilleurArtiste = c.concert().artiste();
      }
    }

    final ConcertBrut cb = gagnant.concert();
    final ConcertBrut fusionne =
        new ConcertBrut(
            meilleurArtiste,
            cb.date(),
            cb.heure(),
            meilleureSalle,
            cb.ville(),
            cb.dept(),
            cb.url(),
            cb.source(),
            cb.classification(),
            cb.genre(),
            cb.statut(),
            cb.presaleDebut(),
            cb.presaleNom(),
            cb.salleSourceId(),
            cb.artisteSourceId());
    return new ConcertEnrichi(
        fusionne, gagnant.artisteNorm(), gagnant.villeNorm(), gagnant.salleNorm());
  }

  private static int scoreNomRiche(final String nom) {
    return (int) nom.codePoints().filter(cp -> cp > 127).count();
  }

  private static int scoreCompletude(final ConcertBrut c) {
    return (c.heure() != null ? 1 : 0)
        + (c.ville() != null ? 1 : 0)
        + (c.dept() != null ? 1 : 0)
        + (c.url() != null ? 1 : 0)
        // Une source qui connaît le statut de vente (TM) ne doit jamais perdre cette
        // information au profit d'une source qui ne le détermine pas (Eventim/Digitick,
        // toujours null) lors d'un départage par complétude.
        + (c.statut() != null ? 1 : 0);
  }
}
