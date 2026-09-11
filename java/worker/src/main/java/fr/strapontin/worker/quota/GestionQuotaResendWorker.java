/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker.quota;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import fr.strapontin.core.CompteurEnvoisResend;
import fr.strapontin.core.RetrySerialisable;
import fr.strapontin.worker.LogsAdminWorker;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Garde-fou quota d'envoi Resend jour/mois côté worker (suite de l'incident).
 *
 * <p>Lecture/écriture SQL brute non chiffrée dans {@code app_config} (clé {@code
 * RESEND_QUOTA_JSON}) — même clé que {@code web.email.GestionQuotaResend}, même pattern que {@link
 * fr.strapontin.worker.quota.GestionQuotaTM}/{@code TM_QUOTA_JSON}. Contournement nécessaire :
 * {@code ConfigServiceWorker} est explicitement en lecture seule. L'alerte de seuil (80%) est
 * portée exclusivement côté web ({@code AlerteQuotaResendService}, polling 15 min sur le même
 * compteur partagé) — le blocage effectif ici insère directement un {@code logs_admin} niveau
 * {@code error}, capté par l'{@code AlerteLogsAdminService} existant, sans nouveau canal d'envoi.
 *
 * @since 2.7.2
 * @author Kwaite
 */
@Service
public class GestionQuotaResendWorker {

  private static final Logger log = LoggerFactory.getLogger(GestionQuotaResendWorker.class);

  static final int LIMITE_JOUR = 100;
  static final int LIMITE_MOIS = 3000;
  private static final int MAX_TENTATIVES = 3;
  private static final String CLE_QUOTA = "RESEND_QUOTA_JSON";

  private final NamedParameterJdbcTemplate jdbc;
  private final ObjectMapper objectMapper;
  private final LogsAdminWorker logsAdminWorker;
  private GestionQuotaResendWorker self;

  public GestionQuotaResendWorker(
      final NamedParameterJdbcTemplate jdbc, final ObjectMapper objectMapper, final LogsAdminWorker logsAdminWorker) {
    this.jdbc = jdbc;
    this.objectMapper = objectMapper;
    this.logsAdminWorker = logsAdminWorker;
  }

  /**
   * Auto-injection par setter (même pattern que {@code web.push.PushService}, v2.6.3) : évite le
   * problème d'auto-référence à la construction — l'appel à {@link #reserverEnvoiTransactionnel}
   * passe ainsi par le proxy AOP Spring et bénéficie de l'isolation {@code SERIALIZABLE}.
   *
   * @param self référence proxifiée de ce bean ({@code @Lazy})
   */
  @Autowired
  public void setSelf(@Lazy final GestionQuotaResendWorker self) {
    this.self = self;
  }

  private Optional<CompteurEnvoisResend> lireCompteurBrut() {
    try {
      final String json =
          jdbc.queryForObject(
              "SELECT valeur_chiffree FROM app_config WHERE cle = :cle",
              Map.of("cle", CLE_QUOTA),
              String.class);
      if (json == null) {
        return Optional.empty();
      }
      return Optional.of(objectMapper.readValue(json, CompteurEnvoisResend.class));
    } catch (EmptyResultDataAccessException e) {
      return Optional.empty();
    } catch (JsonProcessingException e) {
      log.warn("compteur Resend malformé en base — ignoré : {}", e.getMessage());
      return Optional.empty();
    }
  }

  private void ecrireCompteur(final CompteurEnvoisResend compteur) {
    try {
      final String json = objectMapper.writeValueAsString(compteur);
      jdbc.update(
          "INSERT INTO app_config (cle, valeur_chiffree) VALUES (:cle, :json)"
              + " ON CONFLICT (cle) DO UPDATE SET valeur_chiffree = :json, mis_a_jour_le = now()",
          Map.of("cle", CLE_QUOTA, "json", json));
    } catch (JsonProcessingException e) {
      log.error("impossible de sérialiser le compteur Resend", e);
    }
  }

  private static String jourCourant() {
    return LocalDate.now(ZoneOffset.UTC).toString();
  }

  private static String moisCourant(final String jour) {
    return jour.substring(0, 7);
  }

  private static CompteurEnvoisResend appliquerRollover(
      final CompteurEnvoisResend c, final String jour, final String mois) {
    final boolean jourChange = !jour.equals(c.jour());
    final boolean moisChange = !mois.equals(c.mois());
    if (!jourChange && !moisChange) {
      return c;
    }
    return new CompteurEnvoisResend(
        jour,
        jourChange ? 0 : c.envoyesJour(),
        mois,
        moisChange ? 0 : c.envoyesMois(),
        jourChange ? false : c.alerteJourEnvoyee(),
        moisChange ? false : c.alerteMoisEnvoyee());
  }

  private CompteurEnvoisResend lireCompteurCourant() {
    final String jour = jourCourant();
    final String mois = moisCourant(jour);
    return lireCompteurBrut()
        .map(c -> appliquerRollover(c, jour, mois))
        .orElse(new CompteurEnvoisResend(jour, 0, mois, 0, false, false));
  }

  /**
   * Réserve un envoi si le quota jour/mois le permet — bloque avant l'appel réseau Resend.
   *
   * <p>Sur blocage, insère un {@code logs_admin} niveau {@code error} (capté par {@code
   * AlerteLogsAdminService}, digest 15 min). Le COUNT+INSERT est rendu atomique via une
   * transaction {@code SERIALIZABLE} avec retry (même pattern que {@code
   * web.push.PushService.abonner}) — sans cela, deux cycles de digest concurrents lisant tous deux
   * le même compteur avant qu'aucune des deux écritures ne soit committée pouvaient tous deux
   * réserver un envoi, dépassant le quota réel Resend.
   *
   * @return {@code true} si l'envoi est autorisé (compteur incrémenté et persisté), {@code false}
   *   si le quota jour ou mois est atteint
   */
  public boolean reserverEnvoi() {
    return RetrySerialisable.executer(
        MAX_TENTATIVES, log, "la réservation du quota Resend", self::reserverEnvoiTransactionnel);
  }

  /**
   * Partie transactionnelle et isolée de {@link #reserverEnvoi}. {@code REQUIRES_NEW} garantit une
   * transaction dédiée même si appelée depuis une transaction déjà ouverte.
   */
  @Transactional(isolation = Isolation.SERIALIZABLE, propagation = Propagation.REQUIRES_NEW)
  boolean reserverEnvoiTransactionnel() {
    final CompteurEnvoisResend actuel = lireCompteurCourant();
    if (actuel.envoyesJour() >= LIMITE_JOUR || actuel.envoyesMois() >= LIMITE_MOIS) {
      ecrireCompteur(actuel);
      log.error(
          "quota Resend atteint — envoi digest bloqué jour={}/{} mois={}/{}",
          actuel.envoyesJour(),
          LIMITE_JOUR,
          actuel.envoyesMois(),
          LIMITE_MOIS);
      logsAdminWorker.inserer(
          "error",
          "worker",
          "Quota Resend proche de la limite — envois digest email suspendus",
          Map.of(
              "envoyesJour",
              actuel.envoyesJour(),
              "limiteJour",
              LIMITE_JOUR,
              "envoyesMois",
              actuel.envoyesMois(),
              "limiteMois",
              LIMITE_MOIS));
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
}
