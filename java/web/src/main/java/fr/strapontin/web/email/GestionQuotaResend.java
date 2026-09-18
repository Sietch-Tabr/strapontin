/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.email;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import fr.strapontin.core.CompteurEnvoisResend;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

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
  static final int LIMITE_JOUR = 100;
  /* package-private : réutilisé par
   * AlerteQuotaResendService/EmailAlerteService/EmailSender (même package). */
  static final int LIMITE_MOIS = 3000;
  private static final double SEUIL_ALERTE = 0.8;
  private static final String CLE_QUOTA = "RESEND_QUOTA_JSON";

  private final JdbcTemplate jdbc;
  private final ObjectMapper objectMapper;

  /* package-private : instancié uniquement par EmailSender (même package). */
  GestionQuotaResend(final JdbcTemplate jdbc, final ObjectMapper objectMapper) {
    this.jdbc = jdbc;
    this.objectMapper = objectMapper;
  }

  private Optional<CompteurEnvoisResend> lireCompteurBrut() {
    try {
      final String json =
          jdbc.queryForObject(
              "SELECT valeur_chiffree FROM app_config WHERE cle = ?", String.class, CLE_QUOTA);
      if (json == null) {
        return Optional.empty();
      }
      return Optional.of(objectMapper.readValue(json, CompteurEnvoisResend.class));
    } catch (EmptyResultDataAccessException e) {
      return Optional.empty();
    } catch (JsonProcessingException e) {
      LOG.warn("compteur Resend malformé en base — ignoré : {}", e.getMessage());
      return Optional.empty();
    }
  }

  private void ecrireCompteur(final CompteurEnvoisResend compteur) {
    try {
      final String json = objectMapper.writeValueAsString(compteur);
      jdbc.update(
          "INSERT INTO app_config (cle, valeur_chiffree) VALUES (?, ?)"
              + " ON CONFLICT (cle) DO UPDATE SET valeur_chiffree = ?, mis_a_jour_le = now()",
          CLE_QUOTA,
          json,
          json);
    } catch (JsonProcessingException e) {
      LOG.error("impossible de sérialiser le compteur Resend", e);
    }
  }

  private static String jourCourant() {
    return LocalDate.now(ZoneOffset.UTC).toString();
  }

  private static String moisCourant(final String jour) {
    return jour.substring(0, 7);
  }

  private static CompteurEnvoisResend appliquerRollover(
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
    final String jour = jourCourant();
    final String mois = moisCourant(jour);
    return lireCompteurBrut()
        .map(c -> appliquerRollover(c, jour, mois))
        .orElse(new CompteurEnvoisResend(jour, 0, mois, 0, false, false));
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
    if (actuel.envoyesJour() >= LIMITE_JOUR || actuel.envoyesMois() >= LIMITE_MOIS) {
      ecrireCompteur(actuel);
      LOG.error(
          "quota Resend atteint — envoi bloqué jour={}/{} mois={}/{}",
          actuel.envoyesJour(),
          LIMITE_JOUR,
          actuel.envoyesMois(),
          LIMITE_MOIS);
      return false;
    }
    ecrireCompteur(
        new CompteurEnvoisResend(
            actuel.jour(),
            actuel.envoyesJour() + 1,
            actuel.mois(),
            actuel.envoyesMois() + 1,
            actuel.alerteJourEnvoyee(),
            actuel.alerteMoisEnvoyee()));
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
    ecrireCompteur(
        new CompteurEnvoisResend(
            compteur.jour(),
            compteur.envoyesJour(),
            compteur.mois(),
            compteur.envoyesMois(),
            jourSeuil || compteur.alerteJourEnvoyee(),
            moisSeuil || compteur.alerteMoisEnvoyee()));
  }
}
