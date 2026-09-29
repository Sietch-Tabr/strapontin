/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.incident;

import java.time.Instant;

/**
 * Communication d'incident de sécurité publiée par un admin, avec le nombre
 * d'emails et de notifications push effectivement envoyés.
 *
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
