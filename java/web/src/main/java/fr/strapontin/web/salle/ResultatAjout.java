/*
 * Strapontin — concert finder
 * Copyright (C) 2025 Kwaite
 */
package fr.strapontin.web.salle;

/// Résultat de l'opération d'ajout d'une salle.
///
/// @since 2.1.0
/// @author Kwaite
enum ResultatAjout {
  SUCCES,
  DEJA_PRESENTE,
  CAP_ATTEINT,
  NOM_INVALIDE
}
