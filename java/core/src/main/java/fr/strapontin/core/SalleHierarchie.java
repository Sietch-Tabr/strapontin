/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.core;

import java.util.UUID;
import java.util.function.Function;

/**
 * Détection de cycle sur la hiérarchie {@code
 * salles.salle_parent_id} — fonction pure partagée web/worker
 * (détail : CLAUDE.md, Modèle événement canonique).
 * @since 2.9.0
 * @author Kwaite
 */
public final class SalleHierarchie {

  // Garde-fou contre une boucle infinie si la chaîne de parenté déjà en base
  // contient elle-même un cycle préexistant (ne devrait jamais arriver, mais ne
  // doit jamais bloquer indéfiniment).
  private static final int PROFONDEUR_MAX = 100;

  private SalleHierarchie() {}

  /**
   * Indique si assigner {@code candidatParentId} comme parent
   * de {@code salleId} créerait un cycle.
   * @param candidatParentId parent envisagé, ou {@code null}
   * @param salleId salle à laquelle ce parent serait assigné
   * @param lecteurParent fonction retournant le parent connu d'une salle
   * @return {@code true} si cette assignation créerait un cycle
   */
  public static boolean creeraitCycle(
      final UUID candidatParentId, final UUID salleId, final Function<UUID, UUID> lecteurParent) {
    if (candidatParentId == null || salleId == null) {
      return false;
    }
    UUID courant = candidatParentId;
    int profondeur = 0;
    while (courant != null) {
      if (courant.equals(salleId)) {
        return true;
      }
      profondeur++;
      if (profondeur > PROFONDEUR_MAX) {
        return true;
      }
      courant = lecteurParent.apply(courant);
    }
    return false;
  }
}
