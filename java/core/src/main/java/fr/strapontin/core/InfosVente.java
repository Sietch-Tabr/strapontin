/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.core;

/**
 * Statut de vente d'un concert tel que publié par une source.
 *
 * @param statut statut de vente (ex. {@code onsale}, {@code presale}), null si inconnu
 * @param presaleDebut début de la prévente (ISO 8601), null si aucune
 * @param presaleNom nom de la prévente, null si aucune
 * @since 3.0.2
 * @author Kwaite
 */
public record InfosVente(String statut, String presaleDebut, String presaleNom) {

  /** Aucune information de vente. */
  public static final InfosVente INCONNUE = new InfosVente(null, null, null);
}
