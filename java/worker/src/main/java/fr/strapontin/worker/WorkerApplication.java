/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker;

import fr.strapontin.core.FournisseurBouncyCastle;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Point d'entrée du worker Strapontin — application Spring Boot
 * non-web, exécute les jobs Quartz planifiés (fetch, dédoublonnage,
 * persistance, recalcul des matches, notifications).
 * @since 2.0.0
 * @author Kwaite
 */
@SpringBootApplication
public final class WorkerApplication {

  private WorkerApplication() {}

  /**
   * @param args arguments de la ligne de commande
   */
  public static void main(final String[] args) {
    FournisseurBouncyCastle.garantir();
    SpringApplication.run(WorkerApplication.class, args);
  }
}
