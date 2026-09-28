/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.incident;

import java.time.Instant;

/**
 * Communication d'incident de sécurité publiée par un admin.
 *
 * @param id identifiant
 * @param titre titre affiché
 * @param message message affiché
 * @param publieeLe date de publication
 * @param nbEmails emails effectivement envoyés
 * @param nbPush notifications push effectivement envoyées
 * @param changementMdpForce {@code true} si un nouveau mot de passe a été exigé
 * @since 3.0.1
 * @author Kwaite
 */
public record CommunicationIncident(
    long id,
    String titre,
    String message,
    Instant publieeLe,
    int nbEmails,
    int nbPush,
    boolean changementMdpForce) {}
