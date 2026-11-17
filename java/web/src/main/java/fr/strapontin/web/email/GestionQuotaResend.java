/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.email;

import fr.strapontin.core.AccesAppConfig;
import fr.strapontin.core.CompteurEnvoisResend;
import fr.strapontin.core.RegleQuotaResend;
import fr.strapontin.core.StockageCompteurResend;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Garde-fou quota d'envoi Resend jour/mois (détail : CLAUDE.md,
 * section Resend).
 * @since 2.7.2
 * @author Kwaite
 */
@Component
class GestionQuotaResend {

  private static final Logger LOG = LoggerFactory.getLogger(GestionQuotaResend.class);

  /* package-private : réutilisé par
   * AlerteQuotaResendService/EmailAlerteService/EmailSender (même package). */
  static final int LIMITE_JOUR = RegleQuotaResend.LIMITE_JOUR;
  /* package-private : réutilisé par
   * AlerteQuotaResendService/EmailAlerteService/EmailSender (même package). */
  static final int LIMITE_MOIS = RegleQuotaResend.LIMITE_MOIS;
  private static final double SEUIL_ALERTE = 0.8;
  private static final String CLE_QUOTA = RegleQuotaResend.CLE_QUOTA;

  private final StockageCompteurResend stockage;

  /* package-private : instancié uniquement par EmailSender (même package). */
  GestionQuotaResend(final AccesAppConfig acces, final ObjectMapper objectMapper) {
    this.stockage = new StockageCompteurResend(acces, objectMapper);
  }

  /**
   * Lit le compteur courant, applique le rollover jour/mois si
   * nécessaire (sans persister — la persistance n'a lieu que sur un
   * changement d'état réel, {@link #reserverEnvoi()}/{@link
   * #marquerAlerteEnvoyee(CompteurEnvoisResend)}).
   *
   * @return le compteur courant, ou un compteur initial à zéro si absent de la base
   */
  /* package-private : appelé par
   * AlerteQuotaResendService/EmailAlerteService/EmailSender (même package). */
  CompteurEnvoisResend lireCompteurCourant() {
    return RegleQuotaResend.courant(stockage.lireBrut());
  }

  /**
   * Réserve un envoi si le quota le permet, bloque avant
   * l'appel réseau Resend.
   * @return {@code true} si autorisé (compteur incrémenté et
   *     persisté), {@code false} sinon (rollover éventuel
   *     persisté quand même)
   */
  /* package-private : appelé par
   * AlerteQuotaResendService/EmailAlerteService/EmailSender (même package). */
  boolean reserverEnvoi() {
    final CompteurEnvoisResend actuel = lireCompteurCourant();
    if (RegleQuotaResend.estAtteint(actuel)) {
      stockage.ecrire(actuel);
      LOG.error(
          "quota Resend atteint — envoi bloqué jour={}/{} mois={}/{}",
          actuel.envoyesJour(),
          LIMITE_JOUR,
          actuel.envoyesMois(),
          LIMITE_MOIS);
      return false;
    }
    stockage.ecrire(RegleQuotaResend.apresEnvoi(actuel));
    return true;
  }

  /**
   * Indique si le seuil d'alerte (80%) est franchi et pas encore signalé pour
   * la fenêtre courante (jour ou mois).
   *
   * @param compteur le compteur courant (voir {@link #lireCompteurCourant()} )
   * @return {@code true} si une alerte doit être envoyée
   */
  /* package-private : appelé par
   * AlerteQuotaResendService/EmailAlerteService/EmailSender (même package). */
  boolean seuilFranchiNonSignale(final CompteurEnvoisResend compteur) {
    final boolean jourSeuil =
        compteur.envoyesJour() >= LIMITE_JOUR * SEUIL_ALERTE && !compteur.alerteJourEnvoyee();
    final boolean moisSeuil =
        compteur.envoyesMois() >= LIMITE_MOIS * SEUIL_ALERTE && !compteur.alerteMoisEnvoyee();
    return jourSeuil || moisSeuil;
  }

  /**
   * Marque l'alerte de seuil comme envoyée pour la ou les fenêtres actuellement
   * en dépassement, afin de ne pas la renvoyer avant le prochain rollover
   * jour/mois.
   *
   * @param compteur le compteur courant au moment de l'envoi de l'alerte
   */
  /* package-private : appelé par
   * AlerteQuotaResendService/EmailAlerteService/EmailSender (même package). */
  void marquerAlerteEnvoyee(final CompteurEnvoisResend compteur) {
    final boolean jourSeuil = compteur.envoyesJour() >= LIMITE_JOUR * SEUIL_ALERTE;
    final boolean moisSeuil = compteur.envoyesMois() >= LIMITE_MOIS * SEUIL_ALERTE;
    stockage.ecrire(
        new CompteurEnvoisResend(
            compteur.jour(),
            compteur.envoyesJour(),
            compteur.mois(),
            compteur.envoyesMois(),
            jourSeuil || compteur.alerteJourEnvoyee(),
            moisSeuil || compteur.alerteMoisEnvoyee()));
  }

  /**
   * Recale le compteur local sur la consommation réelle déclarée par Resend,
   * sans jamais l'abaisser : le compteur partagé web/worker n'est pas atomique
   * entre les deux JVM et peut sous-compter, jamais l'inverse sans raison.
   * @param envoyesJourReels emails consommés aujourd'hui selon Resend
   * @param envoyesMoisReels emails consommés ce mois selon Resend
   * @return {@code true} si le compteur a été relevé
   */
  /* package-private : appelé par EmailSender (même package). */
  boolean recalerSurUsageReel(final int envoyesJourReels, final int envoyesMoisReels) {
    final CompteurEnvoisResend actuel = lireCompteurCourant();
    if (envoyesJourReels <= actuel.envoyesJour() && envoyesMoisReels <= actuel.envoyesMois()) {
      return false;
    }
    stockage.ecrire(
        new CompteurEnvoisResend(
            actuel.jour(),
            Math.max(actuel.envoyesJour(), envoyesJourReels),
            actuel.mois(),
            Math.max(actuel.envoyesMois(), envoyesMoisReels),
            actuel.alerteJourEnvoyee(),
            actuel.alerteMoisEnvoyee()));
    LOG.info(
        "compteur Resend recalé sur l'usage réel jour {}→{} mois {}→{}",
        actuel.envoyesJour(),
        Math.max(actuel.envoyesJour(), envoyesJourReels),
        actuel.envoyesMois(),
        Math.max(actuel.envoyesMois(), envoyesMoisReels));
    return true;
  }
}
