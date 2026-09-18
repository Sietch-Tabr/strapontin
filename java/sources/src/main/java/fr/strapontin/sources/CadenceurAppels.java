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
}
