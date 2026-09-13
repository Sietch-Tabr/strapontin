/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.securite;

/**
 * Résultat d'une vérification de challenge TOTP ({@link TotpChallengeService#verifier}).
 *
 * @param statut         issue du challenge
 * @param essaisRestants nombre d'essais restants avant verrouillage (uniquement pertinent pour
 *                       {@link Statut#ECHEC})
 * @since 2.7.0
 * @author Kwaite
 */
public record ResultatTotp(Statut statut, int essaisRestants) {

  /**
   * Issue possible d'un challenge TOTP.
   */
  public enum Statut {
    /**
     * Le compte était déjà verrouillé avant même la tentative (aucune
     * vérification effectuée).
     */
    DEJA_VERROUILLE,
    /**
     * Code correct — challenge validé, compteur d'échecs remis à zéro.
     */
    SUCCES,
    /**
     * Code incorrect — au moins un essai restant avant verrouillage.
     */
    ECHEC,
    /**
     * Code incorrect et ce dernier échec vient de franchir le seuil de
     * verrouillage.
     */
    VERROUILLE_MAINTENANT
  }

  /* package-private : appelé par TotpChallengeService (même package). */
  static ResultatTotp dejaVerrouille() {
    return new ResultatTotp(Statut.DEJA_VERROUILLE, 0);
  }

  /* package-private : appelé par TotpChallengeService (même package). */
  static ResultatTotp succes() {
    return new ResultatTotp(Statut.SUCCES, 0);
  }

  /* package-private : appelé par TotpChallengeService (même package). */
  static ResultatTotp echec(final int essaisRestants) {
    return new ResultatTotp(Statut.ECHEC, essaisRestants);
  }

  /* package-private : appelé par TotpChallengeService (même package). */
  static ResultatTotp verrouilleMaintenant() {
    return new ResultatTotp(Statut.VERROUILLE_MAINTENANT, 0);
  }
}
