/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.sources;

/**
 * Garantit un écart minimal entre deux appels HTTP vers un même service
 * externe — détail (implémentations mémoire/JDBC) : CLAUDE.md, Jobs
 * background.
 * @since 2.8.1
 * @author Kwaite
 */
@FunctionalInterface
public interface CadenceurAppels {

  /**
   * Bloque l'appelant si nécessaire pour respecter l'écart minimal depuis le
   * dernier appel.
   */
  void attendre();

  /**
   * Signale que le service distant a refusé un appel pour excès de débit :
   * l'écart minimal s'allonge tant que le service n'a pas récupéré. Sans effet
   * par défaut.
   */
  default void ralentir() {}

  /**
   * Signale un appel accepté : après une série d'appels sans refus, l'écart
   * minimal revient vers sa valeur de base. Sans effet par défaut.
   */
  default void apaiser() {}
}
