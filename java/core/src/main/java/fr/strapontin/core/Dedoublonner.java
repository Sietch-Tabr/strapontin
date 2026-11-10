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
 * Dédoublonne des concerts issus de plusieurs sources (TM, Eventim)
 * en trois passes de rapprochement progressivement plus tolérantes.
 *
 * @author Kwaite
 */
public final class Dedoublonner {

  private static final double SEUIL_SIMILARITE = 0.85;
  // Poids du score de richesse diacritique dans l'arbitrage du nom de salle
  // retenu par fusionner — favorise une forme accentuée correcte ("Théâtre")
  // sur une casse mixte sans accent.
  private static final int POIDS_DIACRITIQUES = 2;
  private static final String SANS_HEURE = "SANS_HEURE";
  // Tolérance sous laquelle deux heures distinctes restent compatibles pour la
  // fusion, très en dessous de tout écart matinée/soirée réel (toujours de
  // l'ordre de l'heure).
  // Taille d'un groupe ne contenant aucun doublon potentiel (rien à
  // rapprocher).
  private static final int TAILLE_GROUPE_SANS_DOUBLON = 1;

  private Dedoublonner() {}

  private record ConcertEnrichi(
      ConcertBrut concert, String artisteNorm, String villeNorm, String salleNorm) {}

  private static ConcertEnrichi enrichir(final ConcertBrut concert) {
    final String salleNorm = Normalisation.normaliserTexte(concert.salle());
    // Ville absente : repli sur salleNorm plutôt qu'une chaîne vide partagée
    // par tous les concerts sans ville — deux concerts du même artiste le même
    // jour, dans deux villes réellement différentes mais toutes deux non
    // renseignées, ne fusionnent alors que s'ils partagent aussi la même salle.
    final String villeNorm =
        concert.ville() != null ? Normalisation.normaliserVille(concert.ville()) : salleNorm;
    return new ConcertEnrichi(
        concert, Normalisation.normaliserTexte(concert.artiste()), villeNorm, salleNorm);
  }

  /**
   * Dédoublonne en trois passes progressivement plus tolérantes :
   * égalité exacte, similarité floue de l'artiste à date/ville
   * identiques, puis repli sur la salle (ville divergente).
   * @param concerts les concerts bruts, éventuellement en double
   * @return les concerts dédoublonnés, doublons fusionnés
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

  // Passe 1 : exacte — (artiste_norm, date, heure, ville_norm). L'heure est
  // incluse : sans elle, deux concerts réellement distincts (matinée + soirée,
  // même artiste/date/ville) fusionnaient à tort dès cette première passe.
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
      if (groupe.size() <= TAILLE_GROUPE_SANS_DOUBLON) {
        resultat.addAll(groupe);
      } else {
        resultat.addAll(grouperParSimilarite(groupe));
      }
    }
    return resultat;
  }

  // Fallback : (date, salle_norm) pour les grandes salles avec villes
  // divergentes entre sources.
  private static List<ConcertBrut> passeFallbackDateSalle(
      final List<ConcertEnrichi> apresPasseFuzzy) {
    final Map<String, List<ConcertEnrichi>> groupes = new LinkedHashMap<>();
    for (final ConcertEnrichi c : apresPasseFuzzy) {
      final String cle = c.concert().date() + "|" + c.salleNorm();
      groupes.computeIfAbsent(cle, _ -> new ArrayList<>()).add(c);
    }
    final List<ConcertBrut> resultat = new ArrayList<>();
    for (final List<ConcertEnrichi> groupe : groupes.values()) {
      if (groupe.size() <= TAILLE_GROUPE_SANS_DOUBLON) {
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
   * Indique si deux concerts peuvent fusionner du point de vue
   * de l'heure (détail : CLAUDE.md, Dédoublonnage inter-sources).
   * @param concertA premier concert enrichi
   * @param concertB second concert enrichi
   * @return {@code true} si les heures ne s'opposent pas à une fusion
   */
  private static boolean heuresCompatibles(
      final ConcertEnrichi concertA, final ConcertEnrichi concertB) {
    final String heureA = concertA.concert().heure();
    final String heureB = concertB.concert().heure();
    if (heureA == null || heureB == null || heureA.equals(heureB)) {
      return true;
    }
    final Integer minutesA = minutesDepuisMinuit(heureA);
    final Integer minutesB = minutesDepuisMinuit(heureB);
    if (minutesA == null || minutesB == null) {
      return false;
    }
    return Math.abs(minutesA - minutesB) <= ReglesCommunes.TOLERANCE_HEURE_MINUTES;
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

  // equalsIgnoreCase ne convient pas ici : le test porte sur chaque nom
  // lui-même (déjà entièrement majuscule ou non), pas sur une comparaison
  // insensible à la casse entre deux chaînes distinctes.
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
        cb.copier().artiste(meilleurArtiste).salle(meilleureSalle).construire();
    return new ConcertEnrichi(
        fusionne, gagnant.artisteNorm(), gagnant.villeNorm(), gagnant.salleNorm());
  }

  private static int scoreNomRiche(final String nom) {
    return (int) nom.codePoints().filter(cp -> cp > 127).count();
  }

  private static int scoreCompletude(final ConcertBrut concert) {
    return (concert.heure() != null ? 1 : 0)
        + (concert.ville() != null ? 1 : 0)
        + (concert.dept() != null ? 1 : 0)
        + (concert.url() != null ? 1 : 0)
        // Une source qui connaît le statut de vente (TM) ne doit jamais perdre
        // cette information au profit d'une source qui ne le détermine pas
        // (Eventim, toujours null) lors d'un départage par complétude.
        + (concert.statut() != null ? 1 : 0);
  }
}
