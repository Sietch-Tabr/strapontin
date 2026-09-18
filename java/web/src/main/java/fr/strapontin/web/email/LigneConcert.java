/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.email;

/**
 * Ligne de concert dans le digest email, champs
 * auto-descriptifs (seul {@code url} peut être {@code null}).
 * @since 2.2.5
 * @author Kwaite
 */
record LigneConcert(String artiste, String dateFormatee, String lieu, String url) {}
