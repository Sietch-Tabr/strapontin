/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.push;

/**
 * Issue d'un envoi push vers un appareil, avec le message destiné à
 * l'utilisateur quand l'envoi n'a pas eu lieu.
 * @since 3.0.3
 * @author Kwaite
 */
public enum ResultatEnvoiPush {
  /** Notification remise au service de push du navigateur. */
  ENVOYEE(200, null),
  /** Clés VAPID absentes ou inutilisables : rien ne peut être envoyé. */
  SERVICE_NON_CONFIGURE(
      503,
      "Les notifications push ne sont pas disponibles pour le moment."
          + " Prévenez l'administrateur du site."),
  /** Aucun abonnement de cet appareil n'est connu du serveur. */
  APPAREIL_INCONNU(
      409,
      "Cet appareil n'est plus enregistré pour les notifications push."
          + " Désactivez-les puis réactivez-les pour l'enregistrer à nouveau."),
  /** Service de push du navigateur non pris en charge : abonnement retiré. */
  SERVICE_NAVIGATEUR_REFUSE(
      409,
      "Le service de notifications de ce navigateur n'est pas pris en charge."
          + " Essayez avec un autre navigateur."),
  /** Le navigateur a retiré l'abonnement (404/410) : abonnement retiré. */
  APPAREIL_REVOQUE(
      409,
      "Ce navigateur n'accepte plus les notifications de Strapontin."
          + " Désactivez-les puis réactivez-les pour les recevoir à nouveau."),
  /** Échec de l'envoi lui-même (réseau, service de push indisponible). */
  ECHEC_ENVOI(
      502,
      "L'envoi n'a pas abouti. Réessayez dans quelques minutes ;"
          + " si le problème persiste, prévenez l'administrateur du site.");

  private final int statutHttp;
  private final String message;

  ResultatEnvoiPush(final int statutHttp, final String message) {
    this.statutHttp = statutHttp;
    this.message = message;
  }

  /**
   * @return statut HTTP à renvoyer au navigateur
   */
  public int statutHttp() {
    return statutHttp;
  }

  /**
   * @return message en français courant, {@code null} si la notification est envoyée
   */
  public String message() {
    return message;
  }
}
