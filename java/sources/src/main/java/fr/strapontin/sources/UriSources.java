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
 * Construction des URI d'appel aux API des sources.
 *
 * <p>Chaque nom et valeur de paramètre de requête est encodé comme composant de requête, {@code +}
 * compris : un nom d'artiste contenant {@code &}, {@code #}, {@code +} ou {@code =} reste une
 * seule valeur de paramètre. {@code UriComponents.encode()} laisse passer
 * {@code +}, lu comme une espace côté serveur, et une chaîne passée à {@code RestClient.uri(String)}
 * n'encode ni {@code &} ni {@code #}. Le résultat est un {@link URI} déjà encodé, que {@code
 * RestClient.uri(URI)} transmet tel quel.
 *
 * @since 3.0.2
 * @author Kwaite
 */
final class UriSources {

  private UriSources() {}

  /**
   * Construit l'URI encodée d'une requête.
   *
   * @param builder requête non encodée (schéma, hôte, chemin ASCII statique, paramètres bruts)
   * @return l'URI dont chaque paramètre est encodé
   */
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
