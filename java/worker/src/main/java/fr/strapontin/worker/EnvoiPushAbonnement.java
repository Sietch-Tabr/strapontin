/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker;

import fr.strapontin.core.ClientWebPush;
import fr.strapontin.core.EchecPush;
import fr.strapontin.core.EndpointPush;
import java.util.Map;

/**
 * Envoi d'un message push à un abonnement, partagé par le digest et l'annonce de version :
 * contrôle de l'endpoint, envoi, puis suite donnée à la réponse (succès, abonnement supprimé,
 * échec) selon {@link EchecPush}.
 * @since 3.1.4
 * @author Kwaite
 */
final class EnvoiPushAbonnement {

  private static final String CLE_ENDPOINT = "endpoint";

  private final SuiviEchecsPush suiviEchecs;
  private final String sourceLogs;
  private final Runnable surAppel;
  private final Runnable surErreur;

  /**
   * @param suiviEchecs suivi des échecs et révocations d'appareils
   * @param sourceLogs source des entrées de {@code logs_admin}
   * @param surAppel appelé avant chaque envoi réel (compteurs), sans effet si rien à compter
   * @param surErreur appelé pour un échec du canal (exception ou échec de la réponse)
   */
  /* package-private : instancié par les services d'envoi push du worker. */
  EnvoiPushAbonnement(
      final SuiviEchecsPush suiviEchecs,
      final String sourceLogs,
      final Runnable surAppel,
      final Runnable surErreur) {
    this.suiviEchecs = suiviEchecs;
    this.sourceLogs = sourceLogs;
    this.surAppel = surAppel;
    this.surErreur = surErreur;
  }

  /**
   * @param pushService client push VAPID
   * @param abonnement abonnement ciblé (id, endpoint, p256dh, auth)
   * @param userId propriétaire de l'abonnement
   * @param payload corps du message
   * @return {@code true} si le message est parti
   */
  /* package-private : appelé par les services d'envoi push du worker. */
  boolean envoyer(
      final ClientWebPush pushService,
      final Map<String, Object> abonnement,
      final String userId,
      final String payload) {
    if (!EndpointPush.estAutorise((String) abonnement.get(CLE_ENDPOINT))) {
      suiviEchecs.revoquer(
          abonnement,
          userId,
          EchecPush.CAUSE_SERVICE_NON_PRIS_EN_CHARGE,
          "endpoint non autorisé",
          sourceLogs);
      return false;
    }
    surAppel.run();
    final ClientWebPush.Reponse reponse;
    try {
      reponse =
          pushService.envoyer(
              (String) abonnement.get(CLE_ENDPOINT),
              (String) abonnement.get("p256dh"),
              (String) abonnement.get("auth"),
              payload);
    } catch (Exception e) {
      ClientWebPush.restaurerInterruption(e);
      surErreur.run();
      suiviEchecs.echec(abonnement, userId, null, e.getMessage(), sourceLogs);
      return false;
    }
    final int status = reponse.statut();
    switch (EchecPush.interpreter(status)) {
      case SUCCES -> {
        suiviEchecs.succes(abonnement);
        return true;
      }
      // Abonnement supprimé côté navigateur : nettoyage attendu, pas une panne du canal.
      case ABONNEMENT_SUPPRIME ->
          suiviEchecs.revoquer(
              abonnement,
              userId,
              EchecPush.causeLisible(status),
              EchecPush.raison(status, null),
              sourceLogs);
      case ECHEC -> {
        surErreur.run();
        suiviEchecs.echec(abonnement, userId, status, reponse.corps(), sourceLogs);
      }
    }
    return false;
  }
}
