/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.miseajour;

import fr.strapontin.exploitation.Version;

/**
 * Identité du workflow qui doit avoir signé un fichier : dépôt, fichier
 * de workflow et référence git (branche ou étiquette de la release).
 *
 * @since 3.2.0
 * @author Kwaite
 */
public record IdentiteAttendue(String depot, String workflow, String reference) {

  /**
   * Identité d'une release : le workflow {@code ci.yml} exécuté sur son tag.
   *
   * @param depot dépôt {@code propriétaire/nom}
   * @param version version de la release
   * @return l'identité attendue pour les fichiers de cette release
   */
  public static IdentiteAttendue pourRelease(final String depot, final Version version) {
    return new IdentiteAttendue(depot, "ci.yml", "refs/tags/v" + version);
  }

  /**
   * Nom du certificat de signature : l'adresse du workflow et sa référence.
   *
   * @return la valeur attendue du nom alternatif du sujet
   */
  public String sujetCertificat() {
    return "https://github.com/" + depot + "/.github/workflows/" + workflow + "@" + reference;
  }

  /**
   * Adresse du dépôt telle qu'écrite dans le certificat.
   *
   * @return l'adresse HTTPS du dépôt
   */
  public String adresseDepot() {
    return "https://github.com/" + depot;
  }
}
