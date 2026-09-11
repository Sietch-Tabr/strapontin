/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.push;

/**
 * Corps JSON de la requête POST /api/push/tester.
 *
 * @param endpoint endpoint de l'abonnement de l'appareil demandeur, ou {@code null} pour
 *     un repli sur l'abonnement le plus récent
 * @since 2.6.4
 * @author Kwaite
 */
public record RequeteTesterPush(String endpoint) {}
