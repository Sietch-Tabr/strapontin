/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.core;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;

/**
 * Règle du quota d'envois Resend, partagée par le web et le worker qui écrivent
 * le même compteur ({@code app_config}, clé {@value #CLE_QUOTA}) : limites,
 * bascule jour/mois, réservation d'un envoi.
 * @since 3.1.4
 * @author Kwaite
 */
public final class RegleQuotaResend {

  /** Clé {@code app_config} du compteur d'envois. */
  public static final String CLE_QUOTA = "RESEND_QUOTA_JSON";

  /** Envois autorisés par jour (palier gratuit : 100). */
  public static final int LIMITE_JOUR = 100;

  /** Envois autorisés par mois (palier gratuit : 3 000). */
  public static final int LIMITE_MOIS = 3000;

  private RegleQuotaResend() {}

  /**
   * @return le jour courant en UTC, au format ISO
   */
  public static String jourCourant() {
    return LocalDate.now(ZoneOffset.UTC).toString();
  }

  /**
   * @param jour jour ISO
   * @return le mois de ce jour, au format {@code AAAA-MM}
   */
  public static String moisCourant(final String jour) {
    return jour.substring(0, 7);
  }

  /**
   * Compteur courant : celui lu en base, remis à zéro pour la période écoulée
   * (l'alerte envoyée se réarme avec elle), ou un compteur vide s'il n'existe
   * pas.
   * @param brut compteur lu, vide si absent ou illisible
   * @return le compteur à jour
   */
  public static CompteurEnvoisResend courant(final Optional<CompteurEnvoisResend> brut) {
    final String jour = jourCourant();
    final String mois = moisCourant(jour);
    return brut.map(c -> appliquerBascule(c, jour, mois))
        .orElse(new CompteurEnvoisResend(jour, 0, mois, 0, false, false));
  }

  private static CompteurEnvoisResend appliquerBascule(
      final CompteurEnvoisResend compteur, final String jour, final String mois) {
    final boolean jourChange = !jour.equals(compteur.jour());
    final boolean moisChange = !mois.equals(compteur.mois());
    if (!jourChange && !moisChange) {
      return compteur;
    }
    return new CompteurEnvoisResend(
        jour,
        jourChange ? 0 : compteur.envoyesJour(),
        mois,
        moisChange ? 0 : compteur.envoyesMois(),
        !jourChange && compteur.alerteJourEnvoyee(),
        !moisChange && compteur.alerteMoisEnvoyee());
  }

  /**
   * @param compteur compteur à jour
   * @return {@code true} si une limite de jour ou de mois est atteinte
   */
  public static boolean estAtteint(final CompteurEnvoisResend compteur) {
    return compteur.envoyesJour() >= LIMITE_JOUR || compteur.envoyesMois() >= LIMITE_MOIS;
  }

  /**
   * @param compteur compteur à jour
   * @return le même compteur après un envoi réservé
   */
  public static CompteurEnvoisResend apresEnvoi(final CompteurEnvoisResend compteur) {
    return new CompteurEnvoisResend(
        compteur.jour(),
        compteur.envoyesJour() + 1,
        compteur.mois(),
        compteur.envoyesMois() + 1,
        compteur.alerteJourEnvoyee(),
        compteur.alerteMoisEnvoyee());
  }
}
