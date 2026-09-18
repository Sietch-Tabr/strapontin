/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.admin;

/**
 * @param succes true si le worker a accepté le déclenchement
 * @param messageErreur message d'erreur si échec, null si succès
 * @param statutHttp 200/400/502/503 (job inconnu/erreur worker/injoignable)
 * @since 2.2.3
 * @author Kwaite
 */
public record ResultatRelance(boolean succes, String messageErreur, int statutHttp) {}
