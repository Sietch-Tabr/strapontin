/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.sources.eventim;

/**
 * Produit écarté d'une récupération par artiste (soirée d'un autre artiste).
 * @param artiste nom de l'artiste recherché
 * @param url adresse du produit, qui identifie son observation
 * @param titre titre du produit
 * @since 3.0.3
 * @author Kwaite
 */
public record ProduitEcarte(String artiste, String url, String titre) {}
