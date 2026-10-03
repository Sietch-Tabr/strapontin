/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker.quota;

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
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Garde-fou quota d'envoi Resend jour/mois côté worker (détail :
 * CLAUDE.md, section Resend).
 * @since 2.7.2
 * @author Kwaite
 */
@Service
public class GestionQuotaResendWorker {

  private static final Logger LOG = LoggerFactory.getLogger(GestionQuotaResendWorker.class);

  private static final int LIMITE_JOUR = 100;
  private static final int LIMITE_MOIS = 3000;
  private static final int MAX_TENTATIVES = 3;
  private static final String CLE_QUOTA = "RESEND_QUOTA_JSON";

  private final NamedParameterJdbcTemplate jdbc;
  private final ObjectMapper objectMapper;
  private final LogsAdminWorker logsAdminWorker;
  private GestionQuotaResendWorker self;

  /**
   * @param jdbc accès JDBC
   * @param objectMapper sérialisation du quota en JSON
   * @param logsAdminWorker journalisation du blocage en cas de quota épuisé
   */
  public GestionQuotaResendWorker(
      final NamedParameterJdbcTemplate jdbc,
      final ObjectMapper objectMapper,
      final LogsAdminWorker logsAdminWorker) {
    this.jdbc = jdbc;
    this.objectMapper = objectMapper;
    this.logsAdminWorker = logsAdminWorker;
  }

  /**
   * Auto-injection par setter (même pattern que {@code web.push.PushService} ,
   * v2.6.3) : évite le problème d'auto-référence à la construction — l'appel à
   * {@link #reserverEnvoiTransactionnel} passe ainsi par le proxy AOP Spring et
   * bénéficie de l'isolation {@code SERIALIZABLE} .
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
    } catch (JacksonException e) {
      LOG.warn("compteur Resend malformé en base — ignoré : {}", e.getMessage());
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
    } catch (JacksonException e) {
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

  private CompteurEnvoisResend lireCompteurCourant() {
    final String jour = jourCourant();
    final String mois = moisCourant(jour);
    return lireCompteurBrut()
        .map(c -> appliquerRollover(c, jour, mois))
        .orElse(new CompteurEnvoisResend(jour, 0, mois, 0, false, false));
  }

  /**
   * Réserve un envoi si le quota jour/mois le permet, bloque
   * avant l'appel réseau Resend (détail : CLAUDE.md, section
   * Resend).
   * @return {@code true} si l'envoi est autorisé
   */
  public boolean reserverEnvoi() {
    return RetrySerialisable.executer(
        MAX_TENTATIVES, LOG, "la réservation du quota Resend", self::reserverEnvoiTransactionnel);
  }

  /**
   * Partie transactionnelle et isolée de {@link #reserverEnvoi} .
   * {@code REQUIRES_NEW} garantit une transaction dédiée même si appelée depuis
   * une transaction déjà ouverte.
   */
  @Transactional(isolation = Isolation.SERIALIZABLE, propagation = Propagation.REQUIRES_NEW)
  /* package-private : requis pour l'auto-invocation via le proxy self
   * (@Transactional/SERIALIZABLE). */
  boolean reserverEnvoiTransactionnel() {
    final CompteurEnvoisResend actuel = lireCompteurCourant();
    if (actuel.envoyesJour() >= LIMITE_JOUR || actuel.envoyesMois() >= LIMITE_MOIS) {
      ecrireCompteur(actuel);
      LOG.error(
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
