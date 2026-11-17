/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.miseajour;

import fr.strapontin.exploitation.Version;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;

/**
 * Lecture des releases GitHub d'un dépôt public, sans jeton : adresses
 * contrôlées à chaque saut de redirection, taille plafonnée, empreinte
 * SHA-256 calculée pendant le téléchargement.
 *
 * @since 3.2.0
 * @author Kwaite
 */
public final class SourceGithub implements SourceReleases {

  private static final int STATUT_OK = 200;
  private static final int STATUT_ABSENT = 404;
  private static final int REPONSE_JSON_MAX = 2 * 1024 * 1024;
  private static final Duration DELAI_CONNEXION = Duration.ofSeconds(30);

  private final ConfigurationSource configuration;
  private final ClientControle client;

  /**
   * @param client client HTTP, qui ne doit suivre aucune redirection
   * @param configuration réglages de la source
   */
  public SourceGithub(final HttpClient client, final ConfigurationSource configuration) {
    this.configuration = configuration;
    this.client = new ClientControle(client, configuration);
  }

  /**
   * Client HTTP sans suivi automatique des redirections.
   *
   * @return un client dont chaque redirection est contrôlée ici
   */
  public static HttpClient clientSansRedirection() {
    return HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NEVER)
        .connectTimeout(DELAI_CONNEXION)
        .build();
  }

  @Override
  public Optional<ReleaseGithub> release(final Version version) {
    final URI adresse =
        URI.create(
            configuration.apiBase()
                + "/repos/"
                + configuration.depot()
                + "/releases/tags/v"
                + version);
    try (ClientControle.Flux flux = client.demander(adresse, "application/vnd.github+json")) {
      if (flux.statut() == STATUT_ABSENT) {
        return Optional.empty();
      }
      if (flux.statut() != STATUT_OK) {
        throw new SourceInaccessibleException("Réponse GitHub inattendue : " + flux.statut());
      }
      final byte[] corps = flux.contenu().readNBytes(REPONSE_JSON_MAX + 1);
      if (corps.length > REPONSE_JSON_MAX) {
        throw new SourceInaccessibleException("Réponse GitHub trop volumineuse");
      }
      return Optional.of(
          LecteurReleaseJson.lire(
              new String(corps, StandardCharsets.UTF_8), version, client.adresses()));
    } catch (final IOException e) {
      throw new SourceInaccessibleException("Lecture de la release impossible", e);
    }
  }

  @Override
  public Telechargement telecharger(final Asset asset, final Path dossier) {
    if (asset.taille() > configuration.tailleMax()) {
      throw new SourceInaccessibleException("Fichier trop volumineux : " + asset.nom());
    }
    try (ClientControle.Flux flux = client.demander(asset.url(), "application/octet-stream")) {
      if (flux.statut() != STATUT_OK) {
        throw new SourceInaccessibleException("Téléchargement refusé : " + flux.statut());
      }
      return EcritureFichier.ecrire(flux.contenu(), asset, dossier, configuration.tailleMax());
    } catch (final IOException e) {
      throw new SourceInaccessibleException("Téléchargement interrompu : " + asset.nom(), e);
    }
  }
}
