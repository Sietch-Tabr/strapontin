/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.securite;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Appareil d'un utilisateur, identifié par son navigateur, son système et son mode d'affichage
 * (application installée ou navigateur). Source unique du nom affiché dans l'email de nouvelle
 * connexion, la liste des sessions et celle des abonnements push, et de la comparaison qui décide
 * d'une alerte de nouvel appareil.
 *
 * <p>Limites connues : un iPad récent se présente comme un Mac, et Windows 10 et 11 partagent la
 * même valeur de {@code User-Agent}. Aucun modèle d'appareil n'est déduit.
 *
 * @param navigateur navigateur reconnu, null si illisible
 * @param systeme système reconnu, null si illisible
 * @param mode mode d'affichage annoncé par le client
 * @since 3.1.1
 * @author Kwaite
 */
public record NomAppareil(String navigateur, String systeme, Mode mode) {

  /** Mode d'affichage, que seul le client connaît : le {@code User-Agent} est identique. */
  public enum Mode {
    /** Application installée (PWA). */
    PWA,
    /** Onglet de navigateur. */
    NAVIGATEUR,
    /** Non annoncé (ancienne session ou client sans l'en-tête). */
    INCONNU
  }

  /** En-tête envoyé par le front sur chaque appel : {@code pwa} ou {@code navigateur}. */
  public static final String ENTETE_MODE = "X-Mode-Affichage";

  private static final String INCONNU = "Appareil inconnu";

  // Ordre significatif : Edge, Opera et Chrome se disent aussi « Safari » ;
  // Chrome se dit « Chrome ».
  private static final List<Reconnaissance> NAVIGATEURS =
      List.of(
          new Reconnaissance("Edge", List.of("edg/", "edga/", "edgios/")),
          new Reconnaissance("Opera", List.of("opr/", "opera")),
          new Reconnaissance("Firefox", List.of("firefox/", "fxios/")),
          new Reconnaissance("Chrome", List.of("chrome/", "crios/")),
          new Reconnaissance("Safari", List.of("safari/")));

  // Android se dit aussi « Linux » : il passe avant.
  private static final List<Reconnaissance> SYSTEMES =
      List.of(
          new Reconnaissance("iPhone", List.of("iphone")),
          new Reconnaissance("iPad", List.of("ipad")),
          new Reconnaissance("Android", List.of("android")),
          new Reconnaissance("Windows", List.of("windows")),
          new Reconnaissance("ChromeOS", List.of("cros")),
          new Reconnaissance("macOS", List.of("macintosh", "mac os x")),
          new Reconnaissance("Linux", List.of("linux")));

  /**
   * @param userAgent en-tête {@code User-Agent}, peut être null
   * @param modeAffichage valeur de {@link #ENTETE_MODE}, peut être null
   * @return l'appareil reconnu, jamais null
   */
  public static NomAppareil depuis(final String userAgent, final String modeAffichage) {
    final Mode mode = lireMode(modeAffichage);
    if (userAgent == null || userAgent.isBlank()) {
      return new NomAppareil(null, null, mode);
    }
    final String ua = userAgent.toLowerCase(Locale.ROOT);
    return new NomAppareil(reconnaitre(NAVIGATEURS, ua), reconnaitre(SYSTEMES, ua), mode);
  }

  private static Mode lireMode(final String valeur) {
    if (valeur == null) {
      return Mode.INCONNU;
    }
    return switch (valeur.trim().toLowerCase(Locale.ROOT)) {
      case "pwa" -> Mode.PWA;
      case "navigateur" -> Mode.NAVIGATEUR;
      default -> Mode.INCONNU;
    };
  }

  /**
   * @return vrai si ni le navigateur ni le système n'ont été reconnus
   */
  public boolean illisible() {
    return navigateur == null && systeme == null;
  }

  /**
   * @return « PWA sur iPhone », « Safari sur iPhone », « Chrome sur Android »... ou « Appareil
   *     inconnu »
   */
  public String libelle() {
    if (mode == Mode.PWA) {
      return systeme == null ? "PWA" : "PWA sur " + systeme;
    }
    if (illisible()) {
      return INCONNU;
    }
    if (navigateur == null) {
      return systeme;
    }
    return systeme == null ? navigateur : navigateur + " sur " + systeme;
  }

  /**
   * Un appareil dont la version du navigateur ou du système a changé reste le même ; un mode
   * inconnu d'un côté (session antérieure à l'annonce du mode) ne distingue rien.
   *
   * @param autre appareil à comparer
   * @return vrai si même navigateur, même système et mode compatible
   */
  public boolean memeAppareil(final NomAppareil autre) {
    final boolean modeCompatible =
        mode == Mode.INCONNU || autre.mode == Mode.INCONNU || mode == autre.mode;
    return modeCompatible
        && Objects.equals(navigateur, autre.navigateur)
        && Objects.equals(systeme, autre.systeme);
  }

  /** Libellé affiché et marqueurs, en minuscules, qui le font reconnaître. */
  private record Reconnaissance(String libelle, List<String> marqueurs) {}

  private static String reconnaitre(final List<Reconnaissance> candidats, final String ua) {
    return candidats.stream()
        .filter(c -> c.marqueurs().stream().anyMatch(ua::contains))
        .map(Reconnaissance::libelle)
        .findFirst()
        .orElse(null);
  }
}
