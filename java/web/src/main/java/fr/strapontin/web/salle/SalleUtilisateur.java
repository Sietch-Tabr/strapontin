/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.salle;

import java.time.Instant;
import java.util.UUID;

/**
 * Salle suivie par un utilisateur ; {@code couverte} est faux quand aucune
 * source (Ticketmaster, Eventim, site de la salle) ne connaît cette salle :
 * l'absence de concert n'y prouve rien.
 *
 * @since 2.1.0
 * @author Kwaite
 */
public record SalleUtilisateur(UUID id, String nom, Instant createdAt, boolean couverte) {}
