/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.miseajour;

/**
 * Compte rendu de fin de mise à jour envoyé à l'application, qui le journalise
 * et alerte l'administrateur. Un échec d'envoi ne doit jamais faire échouer
 * la mise à jour : l'état écrit sur disque reste la référence.
 *
 * @since 3.2.0
 * @author Kwaite
 */
@FunctionalInterface
public interface Rapporteur {

  /**
   * Envoie l'issue de la mise à jour.
   *
   * @param reussie vrai si la version est installée
   * @param version version visée, {@code inconnue} si la demande était illisible
   * @param message détail en français, sans secret
   */
  void envoyer(boolean reussie, String version, String message);
}
