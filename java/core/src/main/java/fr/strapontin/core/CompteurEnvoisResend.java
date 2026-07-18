/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.core;

/// Compteur d'envois Resend jour/mois, persisté dans {@code app_config} (clé
/// {@code RESEND_QUOTA_JSON}).
///
/// Type partagé entre les modules `web` et `worker` — les deux JVM envoient des emails via le
/// même compte Resend (tier gratuit : 100/jour, 3000/mois) et doivent compter contre le même
/// compteur (#981, suite de l'incident #907). {@code jour}/{@code mois} bornent la fenêtre de
/// comptage courante (rollover appliqué par l'appelant à leur changement) ;
/// {@code alerteJourEnvoyee}/{@code alerteMoisEnvoyee} throttlent l'alerte de seuil à une fois
/// par fenêtre, remises à {@code false} au rollover.
///
/// @param jour               jour ISO (yyyy-MM-dd, UTC) de la fenêtre de comptage courante
/// @param envoyesJour        nombre d'envois comptabilisés pour {@code jour}
/// @param mois               mois ISO (yyyy-MM, UTC) de la fenêtre de comptage courante
/// @param envoyesMois        nombre d'envois comptabilisés pour {@code mois}
/// @param alerteJourEnvoyee  {@code true} si l'alerte de seuil jour a déjà été envoyée pour
///     {@code jour}
/// @param alerteMoisEnvoyee  {@code true} si l'alerte de seuil mois a déjà été envoyée pour
///     {@code mois}
/// @since 2.7.2
/// @author Kwaite
public record CompteurEnvoisResend(
    String jour,
    int envoyesJour,
    String mois,
    int envoyesMois,
    boolean alerteJourEnvoyee,
    boolean alerteMoisEnvoyee) {}
