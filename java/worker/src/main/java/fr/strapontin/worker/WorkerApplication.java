/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker;

import java.security.Security;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Point d'entrée du worker Strapontin.
 *
 * Application Spring Boot non-web. Exécute les jobs Quartz planifiés :
 * fetch concerts (artistes, régions, salles), dédoublonnage, persistance,
 * recalcul des matches et notifications.
 *
 * @since 2.0.0
 * @author Kwaite
 */
@SpringBootApplication
public class WorkerApplication {

  public static void main(String[] args) {
    Security.addProvider(new BouncyCastleProvider());
    SpringApplication.run(WorkerApplication.class, args);
  }
}
