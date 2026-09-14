/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.core;

/**
 * Quota d'API Ticketmaster extrait des headers HTTP Rate-
 * Limit-* (détail : CLAUDE.md, Jobs background), champs
 * auto-descriptifs.
 * @since 2.2.3
 * @author Kwaite
 */
public record QuotaTM(int dispo, int total, long reset, String misAJour) {}
