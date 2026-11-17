/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.miseajour;

import fr.strapontin.exploitation.FichierRelease;
import fr.strapontin.exploitation.Version;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Releases installables lues sur l'API publique de GitHub, gardées dix
 * minutes : publiées, avec tous les fichiers du module de mise à jour ;
 * les pré-versions seulement sur le profil {@code dev}.
 *
 * @since 3.2.0
 * @author Kwaite
 */
@Component
public class ReleasesGithub {

  private static final Logger LOG = LoggerFactory.getLogger(ReleasesGithub.class);
  private static final Duration VALIDITE = Duration.ofMinutes(10);
  private static final Set<String> FICHIERS_ATTENDUS = Set.copyOf(FichierRelease.noms());

  private final RestClient restClient;
  private final JsonMapper mapper;
  private final String depot;
  private final boolean preVersions;
  private final Clock horloge;
  private final AtomicReference<Cache> cache = new AtomicReference<>();

  private record Cache(Instant lecture, List<Version> versions) {}

  /**
   * @param restClient client HTTP partagé
   * @param mapper lecture du JSON de l'API
   * @param environnement profils actifs (pré-versions sur {@code dev})
   * @param depot dépôt {@code propriétaire/nom} des releases
   */
  @Autowired
  public ReleasesGithub(
      final RestClient restClient,
      final JsonMapper mapper,
      final Environment environnement,
      @Value("${mise-a-jour.depot:Sietch-Tabr/strapontin}") final String depot) {
    this(
        restClient,
        mapper,
        environnement.acceptsProfiles(Profiles.of("dev")),
        depot,
        Clock.systemUTC());
  }

  /* package-private : horloge et politique injectées par les tests. */
  ReleasesGithub(
      final RestClient restClient,
      final JsonMapper mapper,
      final boolean preVersions,
      final String depot,
      final Clock horloge) {
    this.restClient = restClient;
    this.mapper = mapper;
    this.preVersions = preVersions;
    this.depot = depot;
    this.horloge = horloge;
  }

  /**
   * Version installable la plus récente, strictement supérieure à celle en
   * service.
   *
   * @param installee version en service
   * @return la version proposée, vide s'il n'y en a pas ou si GitHub ne
   *     répond pas
   */
  public Optional<Version> plusRecente(final Version installee) {
    return versions().stream()
        .filter(v -> v.compareTo(installee) > 0)
        .max(Comparator.naturalOrder());
  }

  private List<Version> versions() {
    final Cache courant = cache.get();
    if (courant != null && courant.lecture().plus(VALIDITE).isAfter(horloge.instant())) {
      return courant.versions();
    }
    try {
      final String json =
          restClient
              .get()
              .uri("https://api.github.com/repos/" + depot + "/releases?per_page=20")
              .header("Accept", "application/vnd.github+json")
              .header("User-Agent", "strapontin-web")
              .retrieve()
              .body(String.class);
      final List<Version> lues = lire(mapper.readTree(json));
      cache.set(new Cache(horloge.instant(), lues));
      return lues;
    } catch (final RestClientException | IllegalArgumentException e) {
      LOG.warn("Releases GitHub illisibles : {}", e.getMessage());
      return courant == null ? List.of() : courant.versions();
    }
  }

  private List<Version> lire(final JsonNode releases) {
    final List<Version> versions = new ArrayList<>();
    for (final JsonNode release : releases) {
      if (release.path("draft").asBoolean(true)
          || (release.path("prerelease").asBoolean(true) && !preVersions)) {
        continue;
      }
      final Set<String> fichiers = new HashSet<>();
      release.path("assets").forEach(asset -> fichiers.add(asset.path("name").asString("")));
      if (fichiers.containsAll(FICHIERS_ATTENDUS)) {
        try {
          versions.add(Version.depuisEtiquette(release.path("tag_name").asString("")));
        } catch (final IllegalArgumentException e) {
          LOG.debug("Étiquette de release ignorée : {}", e.getMessage());
        }
      }
    }
    return versions;
  }
}
