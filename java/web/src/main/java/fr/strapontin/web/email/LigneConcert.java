/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.email;

/**
 * Représente une ligne de concert dans le digest email.
 *
 * @param artiste      le nom de l'artiste (jamais null)
 * @param dateFormatee la date formatée pour l'affichage (jamais null)
 * @param lieu         la salle et/ou la ville (jamais null)
 * @param url          le lien d'achat de billets (null si absent)
 * @since 2.2.5
 * @author Kwaite
 */
record LigneConcert(String artiste, String dateFormatee, String lieu, String url) {}
