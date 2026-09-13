/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.sources;

import fr.strapontin.core.BilanFetch;

/**
 * Capacité optionnelle d'une source ou d'un résolveur à exposer un bilan agrégé
 * d'appels et d'erreurs depuis le dernier prélèvement, pour une détection de
 * panne générique côté worker.
 *
 * @since 2.7.0
 * @author Kwaite
 */
@FunctionalInterface
public interface SourceAvecBilanFetch {

  /**
   * Retourne le bilan cumulé depuis le dernier prélèvement et remet les
   * compteurs à zéro.
   *
   * @return le bilan depuis le dernier prélèvement
   */
  BilanFetch preleverBilan();

  /**
   * Nombre minimal d'appels sur un cycle pour qu'un taux d'échec de 100% escalade en alerte {@code
   * error} plutôt qu'un simple {@code warn} — voir {@link BilanFetch#evaluer}. Une source dotée
   * d'un disjoncteur qui abandonne après peu d'échecs doit surcharger cette valeur au-dessus de ce
   * que son propre disjoncteur peut produire à lui seul, pour ne pas transformer chaque
   * déclenchement du disjoncteur en alerte email automatique.
   *
   * @return seuil minimal d'appels, {@link BilanFetch#SEUIL_APPELS_MIN_ALERTE_DEFAUT} par défaut
   */
  default int seuilAppelsMinAlerte() {
    return BilanFetch.SEUIL_APPELS_MIN_ALERTE_DEFAUT;
  }
}
