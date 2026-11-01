/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.sources.sites;

import fr.strapontin.sources.http.ClientHttpFactory;
import java.net.URI;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

/**
 * Lecture polie des pages publiques d'un site de salle : {@code
 * User-Agent} de robot honnête, {@code robots.txt} respecté, délai entre
 * deux requêtes. Un site qui refuse l'accès (429, 403) n'est pas relancé
 * dans le même passage.
 * @since 3.0.6
 * @author Kwaite
 */
public class ClientSiteSalle {

  private static final Logger LOG = LoggerFactory.getLogger(ClientSiteSalle.class);

  /** {@code User-Agent} déclaré à chaque site, avec une adresse où nous joindre. */
  /* package-private : vérifié par les tests du même package. */
  static final String USER_AGENT = "StrapontinBot/1.0 (+https://strapontin.kwaite.fr)";

  private static final String AGENT_ROBOTS = "strapontinbot";
  private static final Duration DELAI_ENTRE_REQUETES_DEFAUT = Duration.ofSeconds(2);
  private static final int STATUT_ACCES_REFUSE = 403;
  private static final int STATUT_LIMITATION = 429;

  private final RestClient restClient;
  private final Duration delaiEntreRequetes;
  private final Map<String, RobotsTxt> robotsParHote = new HashMap<>();
  private boolean premiereRequete = true;

  /** Client de production : délai de deux secondes entre deux requêtes. */
  public ClientSiteSalle() {
    this(
        RestClient.builder()
            .defaultHeader("User-Agent", USER_AGENT)
            .requestFactory(
                ClientHttpFactory.creerRequestFactory(
                    Duration.ofSeconds(10), Duration.ofSeconds(30)))
            .build(),
        DELAI_ENTRE_REQUETES_DEFAUT);
  }

  /**
   * @param restClient client HTTP (injection de test)
   * @param delaiEntreRequetes pause avant chaque requête après la première
   */
  /* package-private : injection de test (même package). */
  ClientSiteSalle(final RestClient restClient, final Duration delaiEntreRequetes) {
    this.restClient = Objects.requireNonNull(restClient, "restClient");
    this.delaiEntreRequetes = Objects.requireNonNull(delaiEntreRequetes, "delaiEntreRequetes");
  }

  /**
   * Lit une page si le {@code robots.txt} du site l'autorise.
   * @param adresse adresse absolue de la page
   * @return le corps de la réponse
   * @throws IllegalStateException si le robots.txt interdit la page
   */
  public String lire(final String adresse) {
    final URI uri = URI.create(adresse);
    if (!robots(uri).autorise(cheminAvecRequete(uri))) {
      throw new IllegalStateException("robots.txt interdit " + adresse);
    }
    return recuperer(uri);
  }

  private RobotsTxt robots(final URI page) {
    final String hote = page.getScheme() + "://" + page.getAuthority();
    final RobotsTxt connu = robotsParHote.get(hote);
    if (connu != null) {
      return connu;
    }
    RobotsTxt regles;
    try {
      regles = RobotsTxt.analyser(recuperer(URI.create(hote + "/robots.txt")), AGENT_ROBOTS);
    } catch (HttpClientErrorException e) {
      if (e.getStatusCode().value() == STATUT_ACCES_REFUSE
          || e.getStatusCode().value() == STATUT_LIMITATION) {
        throw e;
      }
      // Pas de robots.txt (404) : aucune restriction déclarée.
      regles = RobotsTxt.LIBRE;
    }
    robotsParHote.put(hote, regles);
    return regles;
  }

  private String recuperer(final URI uri) {
    attendre();
    final String corps = restClient.get().uri(uri).retrieve().body(String.class);
    LOG.debug("site de salle lu : {}", uri);
    return corps == null ? "" : corps;
  }

  private void attendre() {
    if (premiereRequete) {
      premiereRequete = false;
      return;
    }
    try {
      Thread.sleep(delaiEntreRequetes);
    } catch (InterruptedException _) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("lecture interrompue");
    }
  }

  private static String cheminAvecRequete(final URI uri) {
    final String chemin =
        uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
    return uri.getRawQuery() == null ? chemin : chemin + "?" + uri.getRawQuery();
  }
}
