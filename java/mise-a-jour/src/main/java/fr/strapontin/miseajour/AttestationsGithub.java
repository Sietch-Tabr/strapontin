/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.miseajour;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Attestations GitHub d'un fichier, lues par empreinte sur l'API publique
 * (sans jeton) avec les mêmes contrôles d'adresses que les releases.
 *
 * @since 3.2.0
 * @author Kwaite
 */
public final class AttestationsGithub implements FournisseurAttestations {

  private static final int STATUT_OK = 200;
  private static final int STATUT_ABSENT = 404;
  private static final int REPONSE_MAX = 8 * 1024 * 1024;
  private static final JsonMapper MAPPEUR = JsonMapper.builder().build();

  private final ConfigurationSource configuration;
  private final ClientControle client;

  /**
   * @param client client HTTP, qui ne doit suivre aucune redirection
   * @param configuration réglages de la source
   */
  public AttestationsGithub(final HttpClient client, final ConfigurationSource configuration) {
    this.configuration = configuration;
    this.client = new ClientControle(client, configuration);
  }

  @Override
  public List<String> bundles(final String depot, final String sha256) {
    final URI adresse =
        URI.create(configuration.apiBase() + "/repos/" + depot + "/attestations/sha256:" + sha256);
    try (ClientControle.Flux flux = client.demander(adresse, "application/vnd.github+json")) {
      if (flux.statut() == STATUT_ABSENT) {
        return List.of();
      }
      if (flux.statut() != STATUT_OK) {
        throw new SourceInaccessibleException("Réponse GitHub inattendue : " + flux.statut());
      }
      final byte[] corps = flux.contenu().readNBytes(REPONSE_MAX + 1);
      if (corps.length > REPONSE_MAX) {
        throw new SourceInaccessibleException("Réponse des attestations trop volumineuse");
      }
      return extraire(new String(corps, StandardCharsets.UTF_8));
    } catch (final IOException e) {
      throw new SourceInaccessibleException("Lecture des attestations impossible", e);
    }
  }

  private static List<String> extraire(final String corps) {
    try {
      final List<String> bundles = new ArrayList<>();
      for (final JsonNode attestation : MAPPEUR.readTree(corps).path("attestations")) {
        final JsonNode bundle = attestation.path("bundle");
        if (!bundle.isMissingNode() && !bundle.isNull()) {
          bundles.add(bundle.toString());
        }
      }
      return bundles;
    } catch (final JacksonException e) {
      throw new SourceInaccessibleException("Réponse des attestations illisible", e);
    }
  }
}
