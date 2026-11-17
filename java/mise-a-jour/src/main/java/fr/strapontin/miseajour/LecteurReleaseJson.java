/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.miseajour;

import fr.strapontin.exploitation.Version;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Lecture de la réponse JSON de l'API GitHub pour une release.
 *
 * @since 3.2.0
 * @author Kwaite
 */
final class LecteurReleaseJson {

  private static final JsonMapper MAPPEUR = JsonMapper.builder().build();

  private LecteurReleaseJson() {}

  /**
   * Lit une release et contrôle son étiquette et ses adresses.
   *
   * @param corps réponse JSON
   * @param version version demandée
   * @param adresses adresses autorisées pour les fichiers
   * @return la release lue
   */
  /* package-private : usage interne à la source de releases. */
  static ReleaseGithub lire(
      final String corps, final Version version, final AdressesAutorisees adresses) {
    try {
      final String etiquette = "v" + version;
      final JsonNode racine = MAPPEUR.readTree(corps);
      if (!etiquette.equals(racine.path("tag_name").asString(""))) {
        throw new SourceInaccessibleException("Étiquette inattendue dans la réponse GitHub");
      }
      return new ReleaseGithub(
          etiquette,
          version,
          racine.path("draft").asBoolean(false),
          racine.path("prerelease").asBoolean(false),
          lireFichiers(racine.path("assets"), adresses));
    } catch (final JacksonException | IllegalArgumentException e) {
      throw new SourceInaccessibleException("Réponse GitHub illisible", e);
    }
  }

  private static Map<String, Asset> lireFichiers(
      final JsonNode noeuds, final AdressesAutorisees adresses) {
    final Map<String, Asset> assets = new LinkedHashMap<>();
    for (final JsonNode noeud : noeuds) {
      final URI url = URI.create(noeud.path("browser_download_url").asString(""));
      adresses.verifier(url);
      final Asset asset =
          new Asset(noeud.path("name").asString(""), url, noeud.path("size").asLong(-1));
      if (assets.put(asset.nom(), asset) != null) {
        throw new SourceInaccessibleException("Fichier de release en double : " + asset.nom());
      }
    }
    return assets;
  }
}
