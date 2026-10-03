/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.core;

/**
 * Statut de vente publié par une source ({@code onsale}, {@code presale}…)
 * et prévente éventuelle (début ISO 8601, nom) ; chaque champ peut être null.
 * @since 3.0.2
 * @author Kwaite
 */
public record InfosVente(String statut, String presaleDebut, String presaleNom) {

  /** Aucune information de vente. */
  public static final InfosVente INCONNUE = new InfosVente(null, null, null);
}
