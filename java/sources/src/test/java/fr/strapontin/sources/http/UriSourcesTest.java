/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.sources.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.net.URI;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.util.MultiValueMap;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Tests unitaires de {@link UriSources} : un nom recherché reste une seule valeur de paramètre,
 * relue à l'identique par le serveur, quels que soient ses caractères.
 *
 * @since 3.0.2
 * @author Kwaite
 */
class UriSourcesTest {

  @ParameterizedTest
  @ValueSource(
      strings = {
        "Simon & Garfunkel",
        "Earth, Wind & Fire",
        "Ed Sheeran #1",
        "C+C Music",
        "x&size=200",
        "Mylène Farmer",
        "100%"
      })
  void encoder_nomAvecCaracteresReserves_resteUneSeuleValeurIntacte(final String nom) {
    final URI uri =
        UriSources.encoder(
            UriComponentsBuilder.fromUriString("https://api.exemple.fr/v1/attractions")
                .queryParam("keyword", nom)
                .queryParam("apikey", "cle"));

    final MultiValueMap<String, String> relus =
        UriComponentsBuilder.fromUri(uri).build(true).getQueryParams();
    assertNull(uri.getRawFragment());
    assertEquals(List.of("keyword", "apikey"), List.copyOf(relus.keySet()));
    assertEquals(nom, decoder(relus.getFirst("keyword")));
    assertEquals("cle", relus.getFirst("apikey"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"C+C Music"})
  void encoder_plus_estEncodePourNePasEtreLuCommeUneEspace(final String nom) {
    final URI uri =
        UriSources.encoder(
            UriComponentsBuilder.fromUriString("https://api.exemple.fr/v1").queryParam("q", nom));

    assertEquals("https://api.exemple.fr/v1?q=C%2BC%20Music", uri.toString());
  }

  private static String decoder(final String valeurEncodee) {
    return org.springframework.web.util.UriUtils.decode(
        valeurEncodee, java.nio.charset.StandardCharsets.UTF_8);
  }
}
