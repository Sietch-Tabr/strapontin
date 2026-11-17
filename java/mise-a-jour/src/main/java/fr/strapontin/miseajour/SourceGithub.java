/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.miseajour;

import fr.strapontin.exploitation.Version;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
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

  private static final int REDIRECTIONS_MAX = 5;
  private static final int STATUT_OK = 200;
  private static final int STATUT_ABSENT = 404;
  private static final int STATUT_REDIRECTION_MIN = 300;
  private static final int STATUT_REDIRECTION_MAX = 399;
  private static final int REPONSE_JSON_MAX = 2 * 1024 * 1024;
  private static final Duration DELAI_CONNEXION = Duration.ofSeconds(30);
  private static final Duration DELAI_REQUETE = Duration.ofMinutes(10);

  private final HttpClient client;
  private final ConfigurationSource configuration;
  private final AdressesAutorisees adresses;

  /**
   * @param client client HTTP, qui ne doit suivre aucune redirection
   * @param configuration réglages de la source
   */
  public SourceGithub(final HttpClient client, final ConfigurationSource configuration) {
    this.client = client;
    this.configuration = configuration;
    this.adresses =
        new AdressesAutorisees(configuration.hotesAutorises(), configuration.httpsExige());
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
    try (HttpFlux flux = demander(adresse, "application/vnd.github+json")) {
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
          LecteurReleaseJson.lire(new String(corps, StandardCharsets.UTF_8), version, adresses));
    } catch (final IOException e) {
      throw new SourceInaccessibleException("Lecture de la release impossible", e);
    }
  }

  @Override
  public Telechargement telecharger(final Asset asset, final Path dossier) {
    if (asset.taille() > configuration.tailleMax()) {
      throw new SourceInaccessibleException("Fichier trop volumineux : " + asset.nom());
    }
    try (HttpFlux flux = demander(asset.url(), "application/octet-stream")) {
      if (flux.statut() != STATUT_OK) {
        throw new SourceInaccessibleException("Téléchargement refusé : " + flux.statut());
      }
      return EcritureFichier.ecrire(flux.contenu(), asset, dossier, configuration.tailleMax());
    } catch (final IOException e) {
      throw new SourceInaccessibleException("Téléchargement interrompu : " + asset.nom(), e);
    }
  }

  private HttpFlux demander(final URI depart, final String accept) {
    URI courante = depart;
    for (int saut = 0; saut <= REDIRECTIONS_MAX; saut++) {
      adresses.verifier(courante);
      final HttpResponse<InputStream> reponse = envoyer(courante, accept);
      if (reponse.statusCode() < STATUT_REDIRECTION_MIN
          || reponse.statusCode() > STATUT_REDIRECTION_MAX) {
        return new HttpFlux(reponse.statusCode(), reponse.body());
      }
      final String emplacement = reponse.headers().firstValue("Location").orElse(null);
      final InputStream corps = reponse.body();
      try (corps) {
        if (emplacement == null) {
          throw new SourceInaccessibleException("Redirection sans adresse");
        }
        courante = courante.resolve(emplacement);
      } catch (final IOException e) {
        throw new SourceInaccessibleException("Redirection illisible", e);
      }
    }
    throw new SourceInaccessibleException("Trop de redirections depuis " + depart);
  }

  private HttpResponse<InputStream> envoyer(final URI adresse, final String accept) {
    final HttpRequest requete =
        HttpRequest.newBuilder(adresse)
            .timeout(DELAI_REQUETE)
            .header("User-Agent", configuration.agent())
            .header("Accept", accept)
            .header("X-GitHub-Api-Version", "2022-11-28")
            .GET()
            .build();
    try {
      return client.send(requete, BodyHandlers.ofInputStream());
    } catch (final IOException e) {
      throw new SourceInaccessibleException("Source injoignable : " + adresse.getHost(), e);
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new SourceInaccessibleException("Requête interrompue", e);
    }
  }

  /** Réponse définitive (hors redirection) : statut et flux à fermer. */
  private record HttpFlux(int statut, InputStream contenu) implements AutoCloseable {

    @Override
    public void close() throws IOException {
      contenu.close();
    }
  }
}
