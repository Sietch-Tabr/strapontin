/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.admin;

/**
 * Résultat métier d'une relance manuelle des fetches via le worker.
 *
 * Découple la couche service du protocole HTTP : {@code AdminController} traduit
 * ce résultat en réponse HTTP.
 *
 * <p>{@code statutHttp} distingue désormais les trois causes d'échec possibles plutôt
 * que de les masquer sous un 503 unique : 400 (job inconnu, erreur cliente), 502 (erreur interne
 * worker, ex. Quartz), 503 (worker réellement injoignable).
 *
 * @param succes        {@code true} si le worker a accepté le déclenchement
 * @param messageErreur message d'erreur si échec, {@code null} si succès
 * @param statutHttp    code HTTP à renvoyer à l'admin (200 si succès)
 * @since 2.2.3
 * @author Kwaite
 */
public record ResultatRelance(boolean succes, String messageErreur, int statutHttp) {}
