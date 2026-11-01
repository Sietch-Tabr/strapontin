/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker.sante;

import fr.strapontin.core.DistanceGeo;
import fr.strapontin.core.Normalisation;
import fr.strapontin.sources.enrichissement.EnrichissementSalle;
import fr.strapontin.sources.enrichissement.SalleAEnrichir;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Cohérence entre une salle et son élément Wikidata : positions si connues,
 * sinon commune contre les villes des concerts — extrait de {@link
 * CoherenceWikidataSalleService}.
 * @since 3.0.2
 * @author Kwaite
 */
final class EvaluationCoherenceWikidata {

  /* package-private : élément sans commune connue, seule sa position peut
   * être comparée. */
  static final String COMMUNE_INCONNUE = "inconnue";

  // une comparaison texte-à-texte stricte ville/commune Wikidata génère du
  // bruit quasi systématique sur toute salle parisienne/lyonnaise/marseillaise
  // (arrondissement ≠ ville mère), en zone urbaine dense (quartiers/districts
  // informels sans commune propre, ex. Bercy, La Défense) ou sur une commune
  // fusionnée/intercommunalité (Wikidata garde parfois l'ancien nom d'une des
  // communes d'origine, ou référence l'intercommunalité). Cette normalisation
  // est volontairement locale à ce contrôle *a posteriori* : {@code
  // WikidataSalleSource} reste inchangé, il bloque le matching *au moment de
  // l'enrichissement* où une comparaison plus permissive réduirait la couverture.
  private static final Pattern ARRONDISSEMENT =
      Pattern.compile("^(\\d{1,2})(?:e|er|eme|ieme)? +arrondissement de (paris|lyon|marseille)$");

  // Quartiers/districts informels ne correspondant à aucune commune
  // administrative, rattachés à une ou plusieurs communes possibles (ex. La
  // Défense chevauche Nanterre/Puteaux/Courbevoie).
  private static final String VILLE_PARIS = "paris";
  private static final Map<String, Set<String>> QUARTIERS_EQUIVALENTS =
      Map.of(
          "bercy", Set.of(VILLE_PARIS),
          "la defense", Set.of("nanterre", "puteaux", "courbevoie", VILLE_PARIS),
          "st germain l auxerrois", Set.of(VILLE_PARIS),
          "notre dame", Set.of(VILLE_PARIS),
          "chaussee d antin", Set.of(VILLE_PARIS),
          // Quartier administratif du 12e arrondissement de Paris où se trouve
          // l'Opéra Bastille.
          "quinze vingts", Set.of(VILLE_PARIS));

  // Longueur minimale pour le repli par containment (commune
  // fusionnée/intercommunalité, ex. « Pierre-Bénite » ⊂ « Oullins-Pierre-Bénite
  // », « Orange » ⊂ « communauté de communes du Pays d'Orange en Provence ») —
  // évite un containment trivial sur un token court.
  private static final int LONGUEUR_MIN_CONTAINMENT = 5;

  /* package-private : partagé avec ControleDispersionGeo. Mesuré sur 163
   * salles : élément juste à 2,4 km au plus de ses lieux publiés, élément d'un
   * autre lieu à 5,5 km au moins (Paul Fort / Paul Bouin). */
  static final double DISTANCE_AUTRE_LIEU_METRES = 4_000;

  private EvaluationCoherenceWikidata() {}

  /**
   * Partielle : une minorité de concerts mal rattachée à une salle bien
   * enrichie ; totale : mauvais rapprochement Wikidata.
   */
  /* package-private : lu par CoherenceWikidataSalleService. */
  enum Coherence {
    COHERENTE,
    INCOHERENTE_PARTIELLE,
    INCOHERENTE_TOTALE
  }

  // Positions de l'élément et des lieux publiés si connues (la commune signalait
  // à tort une agglomération : Pin Galant, Mérignac/Bordeaux), sinon communes.
  /* package-private : appelé par CoherenceWikidataSalleService. */
  static Coherence evaluer(
      final SalleAEnrichir salle,
      final EnrichissementSalle enrichissement,
      final String communeWikidata,
      final List<String> villesConcertsReelles) {
    if (salle.latitude() != null && enrichissement.latitude() != null) {
      return coherenceParPosition(salle, enrichissement);
    }
    if (COMMUNE_INCONNUE.equals(communeWikidata) || communeWikidata.isBlank()) {
      return null;
    }
    return coherenceParCommune(salle, communeWikidata, villesConcertsReelles);
  }

  private static Coherence coherenceParPosition(
      final SalleAEnrichir salle, final EnrichissementSalle enrichissement) {
    return DistanceGeo.metres(
                salle.latitude(),
                salle.longitude(),
                enrichissement.latitude(),
                enrichissement.longitude())
            <= DISTANCE_AUTRE_LIEU_METRES
        ? Coherence.COHERENTE
        : Coherence.INCOHERENTE_TOTALE;
  }

  private static Coherence coherenceParCommune(
      final SalleAEnrichir salle,
      final String communeWikidata,
      final List<String> villesConcertsReelles) {
    if (villesConcertsReelles != null && !villesConcertsReelles.isEmpty()) {
      final long nbCoherentes =
          villesConcertsReelles.stream().filter(v -> villesCoherentes(v, communeWikidata)).count();
      if (nbCoherentes == villesConcertsReelles.size()) {
        return Coherence.COHERENTE;
      }
      return nbCoherentes > 0 ? Coherence.INCOHERENTE_PARTIELLE : Coherence.INCOHERENTE_TOTALE;
    }
    return villesCoherentes(salle.ville(), communeWikidata)
        ? Coherence.COHERENTE
        : Coherence.INCOHERENTE_TOTALE;
  }

  private static boolean villesCoherentes(final String villeSalle, final String communeWikidata) {
    final String villeSalleNorm = Normalisation.normaliserVille(villeSalle);
    final String communeNorm = Normalisation.normaliserVille(communeWikidata);
    if (villeSalleNorm.equals(communeNorm)) {
      return true;
    }
    return !Collections.disjoint(canonicaliser(villeSalleNorm), canonicaliser(communeNorm))
        || containmentCoherent(villeSalleNorm, communeNorm);
  }

  private static Set<String> canonicaliser(final String villeNorm) {
    final Matcher matcher = ARRONDISSEMENT.matcher(villeNorm);
    if (matcher.matches()) {
      return Set.of(matcher.group(2));
    }
    final Set<String> quartier = QUARTIERS_EQUIVALENTS.get(villeNorm);
    if (quartier != null) {
      return quartier;
    }
    return Set.of(villeNorm);
  }

  private static boolean containmentCoherent(final String nomA, final String nomB) {
    if (nomA.length() < LONGUEUR_MIN_CONTAINMENT || nomB.length() < LONGUEUR_MIN_CONTAINMENT) {
      return false;
    }
    return nomA.contains(nomB) || nomB.contains(nomA);
  }
}
