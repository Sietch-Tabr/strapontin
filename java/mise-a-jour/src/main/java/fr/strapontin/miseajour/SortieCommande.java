/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.miseajour;

/**
 * Résultat d'une commande système.
 *
 * @param code code de sortie du processus
 * @param sortie sortie standard et d'erreur réunies
 * @since 3.2.0
 * @author Kwaite
 */
public record SortieCommande(int code, String sortie) {}
