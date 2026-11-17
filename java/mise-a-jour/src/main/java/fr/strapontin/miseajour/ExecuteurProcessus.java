/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.miseajour;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * Exécuteur de production : un processus fils du système.
 *
 * @since 3.2.0
 * @author Kwaite
 */
public final class ExecuteurProcessus implements ExecuteurCommande {

  /** Exécuteur sans état. */
  public ExecuteurProcessus() {
    // Rien à initialiser.
  }

  @Override
  public SortieCommande executer(
      final List<String> commande, final Map<String, String> environnement) {
    final ProcessBuilder constructeur = new ProcessBuilder(commande).redirectErrorStream(true);
    constructeur.environment().putAll(environnement);
    try {
      final Process processus = constructeur.start();
      try (InputStream flux = processus.getInputStream()) {
        final String sortie = new String(flux.readAllBytes(), StandardCharsets.UTF_8);
        return new SortieCommande(processus.waitFor(), sortie);
      }
    } catch (final IOException e) {
      throw new InstallationException("Lancement impossible : " + commande.get(0), e);
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new InstallationException("Commande interrompue : " + commande.get(0), e);
    }
  }
}
