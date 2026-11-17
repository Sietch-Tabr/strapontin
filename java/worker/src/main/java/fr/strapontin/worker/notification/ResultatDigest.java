/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker.notification;

import fr.strapontin.worker.notification.NotificationDigestService.ConcertNotif;
import java.util.ArrayList;
import java.util.List;

/**
 * Résultat d'un envoi de digest, avec le sous-ensemble de concerts
 * effectivement tenté par canal — nécessaire pour ne marquer
 * {@code notification_envoyee_le} que sur les concerts réellement délivrés
 * par un canal en succès, pas sur l'ensemble du digest dès qu'un seul canal a
 * réussi.
 */
record ResultatDigest(
    boolean emailOk,
    boolean pushOk,
    List<ConcertNotif> concertsEmail,
    List<ConcertNotif> concertsPush) {

  // Identifiants des concerts livrés par un canal en succès.
  /* package-private : appelé par NotificationDigestService (même package). */
  List<String> concertIdsNotifies() {
    final List<String> ids = new ArrayList<>();
    if (emailOk) {
      concertsEmail.forEach(c -> ids.add(c.concertId()));
    }
    if (pushOk) {
      concertsPush.forEach(c -> ids.add(c.concertId()));
    }
    return ids;
  }

  // Email ou push sans objet, sans service d'envoi configuré ou (push) sans
  // appareil abonné : pas un échec, l'utilisateur n'a aucun canal joignable.
  /* package-private : appelé par NotificationDigestService (même package). */
  boolean sansCanal(final boolean abonnePush, final boolean emailDisponible) {
    return (concertsEmail.isEmpty() || !emailDisponible) && (concertsPush.isEmpty() || !abonnePush);
  }
}
