/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.core;

/**
 * Compteur d'envois Resend jour/mois, partagé web/worker
 * (détail : CLAUDE.md, section Resend), champs auto-descriptifs.
 * @since 2.7.2
 * @author Kwaite
 */
public record CompteurEnvoisResend(
    String jour,
    int envoyesJour,
    String mois,
    int envoyesMois,
    boolean alerteJourEnvoyee,
    boolean alerteMoisEnvoyee) {}
