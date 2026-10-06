/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.sources;

/**
 * Produit d'une récupération par artiste écarté comme n'étant pas une date de
 * cet artiste (soirée de festival d'un autre artiste).
 *
 * @param artiste nom de l'artiste recherché
 * @param url adresse du produit, identifiant de son observation
 * @param titre titre du produit
 * @since 3.0.3
 * @author Kwaite
 */
public record ProduitEcarte(String artiste, String url, String titre) {}
