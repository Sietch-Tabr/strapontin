/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.sources;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import org.springframework.web.util.UriComponents;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.web.util.UriUtils;

/**
 * URI d'appel aux sources, paramètres encodés en composant de requête,
 * {@code +} compris (que {@code encode()} laisse passer) : un nom contenant
 * {@code & # + =} reste une seule valeur. URI déjà encodée, transmise telle
 * quelle par {@code RestClient.uri(URI)}.
 * @since 3.0.2
 * @author Kwaite
 */
final class UriSources {

  private UriSources() {}

  /**
   * Construit l'URI encodée d'une requête.
   *
   * @param builder requête non encodée (chemin ASCII statique, paramètres bruts)
   * @return l'URI dont chaque paramètre est encodé
   */
  /* package-private : utilitaire des clients du package. */
  static URI encoder(final UriComponentsBuilder builder) {
    Objects.requireNonNull(builder, "builder");
    final UriComponents brut = builder.build();
    final UriComponentsBuilder encode =
        UriComponentsBuilder.newInstance()
            .scheme(brut.getScheme())
            .host(brut.getHost())
            .port(brut.getPort())
            .path(brut.getPath());
    brut.getQueryParams()
        .forEach(
            (nom, valeurs) ->
                valeurs.forEach(
                    valeur ->
                        encode.queryParam(
                            encoderComposant(nom),
                            valeur == null ? null : encoderComposant(valeur))));
    return encode.build(true).toUri();
  }

  private static String encoderComposant(final String valeur) {
    return UriUtils.encodeQueryParam(valeur, StandardCharsets.UTF_8).replace("+", "%2B");
  }
}
