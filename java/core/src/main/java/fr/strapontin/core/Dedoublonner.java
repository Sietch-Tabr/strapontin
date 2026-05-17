package fr.strapontin.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class Dedoublonner {

  private static final double SEUIL_SIMILARITE = 0.85;

  private Dedoublonner() {}

  private record ConcertEnrichi(
      ConcertBrut concert, String artisteNorm, String villeNorm, String salleNorm) {}

  private static ConcertEnrichi enrichir(ConcertBrut c) {
    return new ConcertEnrichi(
        c,
        Normalisation.normaliserTexte(c.artiste()),
        c.ville() != null ? Normalisation.normaliserVille(c.ville()) : "",
        Normalisation.normaliserTexte(c.salle()));
  }

  public static List<ConcertBrut> dedoublonner(List<ConcertBrut> concerts) {
    if (concerts.isEmpty()) return List.of();

    List<ConcertEnrichi> enrichis = concerts.stream().map(Dedoublonner::enrichir).toList();

    // Passe 1 : exacte — (artiste_norm, date, ville_norm)
    Map<String, List<ConcertEnrichi>> groupesExacts = new LinkedHashMap<>();
    for (ConcertEnrichi c : enrichis) {
      String cle = c.artisteNorm() + "|" + c.concert().date() + "|" + c.villeNorm();
      groupesExacts.computeIfAbsent(cle, _ -> new ArrayList<>()).add(c);
    }

    List<ConcertEnrichi> apresPasseExacte = new ArrayList<>();
    for (List<ConcertEnrichi> groupe : groupesExacts.values()) {
      apresPasseExacte.add(fusionner(groupe));
    }

    // Passe 2 : fuzzy — (date, ville_norm) + similarité artiste >= seuil
    Map<String, List<ConcertEnrichi>> groupesDateVille = new LinkedHashMap<>();
    for (ConcertEnrichi c : apresPasseExacte) {
      String cle = c.concert().date() + "|" + c.villeNorm();
      groupesDateVille.computeIfAbsent(cle, _ -> new ArrayList<>()).add(c);
    }

    List<ConcertEnrichi> apresPasseFuzzy = new ArrayList<>();
    for (List<ConcertEnrichi> groupe : groupesDateVille.values()) {
      if (groupe.size() <= 1) {
        apresPasseFuzzy.addAll(groupe);
      } else {
        apresPasseFuzzy.addAll(grouperParSimilarite(groupe));
      }
    }

    // Fallback : (date, salle_norm) pour les grandes salles avec villes divergentes
    Map<String, List<ConcertEnrichi>> groupesDateSalle = new LinkedHashMap<>();
    for (ConcertEnrichi c : apresPasseFuzzy) {
      String cle = c.concert().date() + "|" + c.salleNorm();
      groupesDateSalle.computeIfAbsent(cle, _ -> new ArrayList<>()).add(c);
    }

    List<ConcertBrut> resultat = new ArrayList<>();
    for (List<ConcertEnrichi> groupe : groupesDateSalle.values()) {
      if (groupe.size() <= 1) {
        resultat.add(groupe.get(0).concert());
      } else {
        grouperParSimilarite(groupe).forEach(c -> resultat.add(c.concert()));
      }
    }

    return resultat;
  }

  private static List<ConcertEnrichi> grouperParSimilarite(List<ConcertEnrichi> groupe) {
    List<ConcertEnrichi> fusionnes = new ArrayList<>();
    boolean[] traites = new boolean[groupe.size()];

    for (int i = 0; i < groupe.size(); i++) {
      if (traites[i]) continue;
      List<ConcertEnrichi> sousGroupe = new ArrayList<>();
      sousGroupe.add(groupe.get(i));
      traites[i] = true;

      for (int j = i + 1; j < groupe.size(); j++) {
        if (traites[j]) continue;
        double sim =
            Normalisation.similarite(groupe.get(i).artisteNorm(), groupe.get(j).artisteNorm());
        if (sim >= SEUIL_SIMILARITE) {
          sousGroupe.add(groupe.get(j));
          traites[j] = true;
        }
      }
      fusionnes.add(fusionner(sousGroupe));
    }
    return fusionnes;
  }

  private static ConcertEnrichi fusionner(List<ConcertEnrichi> concerts) {
    ConcertEnrichi gagnant =
        concerts.stream()
            .reduce(
                (meilleur, candidat) -> {
                  int scoreM = scoreCompletude(meilleur.concert());
                  int scoreC = scoreCompletude(candidat.concert());
                  if (scoreC > scoreM) return candidat;
                  if (scoreC == scoreM) {
                    return scoreNomRiche(candidat.concert().artiste())
                            > scoreNomRiche(meilleur.concert().artiste())
                        ? candidat
                        : meilleur;
                  }
                  return meilleur;
                })
            .orElseThrow();

    String meilleureSalle = gagnant.concert().salle();
    for (ConcertEnrichi c : concerts) {
      int scoreM =
          scoreNomRiche(meilleureSalle) * 2
              + (!meilleureSalle.equals(meilleureSalle.toUpperCase()) ? 1 : 0);
      int scoreC =
          scoreNomRiche(c.concert().salle()) * 2
              + (!c.concert().salle().equals(c.concert().salle().toUpperCase()) ? 1 : 0);
      if (scoreC > scoreM) meilleureSalle = c.concert().salle();
    }

    String meilleurArtiste = gagnant.concert().artiste();
    for (ConcertEnrichi c : concerts) {
      if (scoreNomRiche(c.concert().artiste()) > scoreNomRiche(meilleurArtiste)) {
        meilleurArtiste = c.concert().artiste();
      }
    }

    ConcertBrut cb = gagnant.concert();
    ConcertBrut fusionne =
        new ConcertBrut(
            meilleurArtiste,
            cb.date(),
            cb.heure(),
            meilleureSalle,
            cb.ville(),
            cb.dept(),
            cb.prixMin(),
            cb.prixMax(),
            cb.url(),
            cb.source(),
            cb.classification(),
            cb.genre(),
            cb.statut());
    return new ConcertEnrichi(
        fusionne, gagnant.artisteNorm(), gagnant.villeNorm(), gagnant.salleNorm());
  }

  private static int scoreNomRiche(String nom) {
    return (int) nom.codePoints().filter(cp -> cp > 127).count();
  }

  private static int scoreCompletude(ConcertBrut c) {
    return (c.heure() != null ? 1 : 0)
        + (c.ville() != null ? 1 : 0)
        + (c.dept() != null ? 1 : 0)
        + (c.prixMin() != null ? 1 : 0)
        + (c.url() != null ? 1 : 0);
  }
}
