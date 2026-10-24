/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.core;

import java.util.Set;
import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Nom de salle qualifié d'une sous-salle (« Complexe - Salle ») : extraction
 * du complexe et comparaison des qualificatifs.
 * @since 3.0.4
 * @author Kwaite
 */
public final class QualificatifSalle {

  // Tiret précédé ou suivi d'une espace (« Th du Rond Point -Salle Jean Tardieu ») ;
  // un tiret accolé des deux côtés appartient au nom (« Saint-Louis »).
  private static final Pattern SEPARATEUR_QUALIFICATIF = Pattern.compile("\\s+-\\s*|\\s*-\\s+");
  // Abréviation de billetterie en tête du nom du complexe.
  private static final Pattern ABREVIATION_THEATRE =
      Pattern.compile("^th\\.?\\s+", Pattern.CASE_INSENSITIVE);

  // « Divers lieux » regroupe plusieurs sites d'un quartier : ce n'est la
  // salle interne d'aucun complexe.
  private static final Pattern PSEUDO_LIEU =
      Pattern.compile("^(?:(?:divers|plusieurs|differents|multiples|autres) )?lieux(?: divers)?$");

  private QualificatifSalle() {}

  // Le séparateur ne survit pas à normaliserTexte (tirets remplacés par une
  // espace) — l'extraction opère donc sur le nom brut, jamais sur nom_norm.
  /**
   * @param nomBrut nom publié, éventuellement qualifié (« Complexe - Salle »)
   * @param nomNormCandidat nom normalisé d'une salle trouvée pour ce nom
   * @return {@code true} si le nom est qualifié et que la salle trouvée n'en
   *     porte pas le qualificatif : c'est le complexe, pas sa salle interne
   */
  public static boolean qualificatifAbsentDe(final String nomBrut, final String nomNormCandidat) {
    final String qualif = extraireQualificatif(nomBrut);
    if (qualif == null || nomNormCandidat == null) {
      return false;
    }
    final Set<String> motsQualif =
        CorrespondanceSalle.tokensSignificatifs(Normalisation.normaliserTexte(qualif));
    return !motsQualif.isEmpty()
        && !CorrespondanceSalle.tokensSignificatifs(nomNormCandidat).containsAll(motsQualif);
  }

  private static String extraireQualificatif(final String nomBrut) {
    final MatchResult separateur = dernierSeparateur(nomBrut);
    if (separateur == null) {
      return null;
    }
    final String qualif = nomBrut.substring(separateur.end()).trim();
    if (qualif.isEmpty() || PSEUDO_LIEU.matcher(Normalisation.normaliserTexte(qualif)).matches()) {
      return null;
    }
    return qualif;
  }

  private static MatchResult dernierSeparateur(final String nomBrut) {
    final Matcher matcher = SEPARATEUR_QUALIFICATIF.matcher(nomBrut);
    MatchResult dernier = null;
    while (matcher.find()) {
      if (matcher.start() > 0) {
        dernier = matcher.toMatchResult();
      }
    }
    return dernier;
  }

  /**
   * Retire du nom la ville qui le termine (« La Merise - Trappes » à Trappes) :
   * ce n'est pas le qualificatif d'une salle interne, seulement la commune du
   * lieu que certaines billetteries ajoutent à son nom.
   * @param nomBrut nom publié, éventuellement qualifié
   * @param ville commune publiée avec le concert, peut être {@code null}
   * @return le nom sans la commune finale, sinon le nom inchangé
   */
  public static String sansVilleFinale(final String nomBrut, final String ville) {
    final String qualif = nomBrut == null || ville == null ? null : extraireQualificatif(nomBrut);
    if (qualif == null
        || !Normalisation.normaliserTexte(qualif).equals(Normalisation.normaliserTexte(ville))) {
      return nomBrut;
    }
    return nomBrut.substring(0, dernierSeparateur(nomBrut).start()).trim();
  }

  /**
   * Extrait la partie complexe/base d'un nom qualifié (ex. "La Seine
   * Musicale - Grande Seine" → "La Seine Musicale"), pour résoudre le
   * complexe avant de créer/retrouver la sous-salle correspondante.
   * @param nomBrut nom brut (non normalisé)
   * @return le nom de base, ou {@code null} si nomBrut ne porte aucun
   *     qualificatif
   */
  public static String extraireBase(final String nomBrut) {
    final MatchResult separateur = dernierSeparateur(nomBrut);
    if (separateur == null || extraireQualificatif(nomBrut) == null) {
      return null;
    }
    final String base =
        ABREVIATION_THEATRE
            .matcher(nomBrut.substring(0, separateur.start()).trim())
            .replaceFirst("Théâtre ");
    return base.isEmpty() ? null : base;
  }

  /**
   * Indique si deux noms bruts portent un qualificatif de sous-salle
   * distinct — un complexe multi-salles ne fusionne pas deux halls
   * distincts au seul motif du nom du complexe partagé.
   * @param nomBrutA premier nom, non normalisé
   * @param nomBrutB second nom, non normalisé
   * @return {@code true} si les deux portent un qualificatif divergent
   */
  public static boolean qualificatifsDivergents(final String nomBrutA, final String nomBrutB) {
    final String qualifA = extraireQualificatif(nomBrutA);
    final String qualifB = extraireQualificatif(nomBrutB);
    if (qualifA == null || qualifB == null) {
      return false;
    }
    return !CorrespondanceSalle.estDoublonSalle(
        Normalisation.normaliserTexte(qualifA), Normalisation.normaliserTexte(qualifB));
  }
}
