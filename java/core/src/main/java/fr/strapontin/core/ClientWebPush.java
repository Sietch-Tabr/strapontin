/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.core;

import com.interaso.webpush.VapidKeys;
import com.interaso.webpush.WebPush;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;

/**
 * Envoi d'une notification Web Push (chiffrement {@code aes128gcm}, authentification VAPID) par le
 * client HTTP du JDK : la bibliothèque ne fournit que l'en-tête et le corps, le statut et la
 * réponse du service push restent à la disposition des règles de {@link EchecPush}.
 * @since 3.1.1
 * @author Kwaite
 */
public final class ClientWebPush {

  /** Réponse du service push : statut HTTP et début du corps (raison d'un refus). */
  public record Reponse(int statut, String corps) {}

  /** Envoi HTTP d'une requête déjà chiffrée, substituable en test. */
  @FunctionalInterface
  public interface Transport {
    /**
     * @param endpoint adresse d'abonnement
     * @param entetes en-têtes à poser (VAPID, chiffrement, durée de vie)
     * @param corps contenu chiffré
     * @return réponse du service push
     * @throws IOException si le service push est injoignable ou ne répond pas à temps
     * @throws InterruptedException si l'envoi est interrompu
     */
    Reponse poster(String endpoint, Map<String, String> entetes, byte[] corps)
        throws IOException, InterruptedException;
  }

  private static final Duration DELAI_CONNEXION = Duration.ofSeconds(10);
  private static final Duration DELAI_REPONSE = Duration.ofSeconds(20);
  private static final int OCTETS_MAX_CORPS = 2048;

  private final WebPush webPush;
  private final Transport transport;

  /**
   * @param clePublique clé publique VAPID (point non compressé, base64url)
   * @param clePrivee clé privée VAPID (scalaire, base64url)
   * @param sujet identifiant {@code mailto:} ou {@code https:} requis par VAPID
   * @throws IllegalArgumentException si les clés sont invalides
   */
  public ClientWebPush(final String clePublique, final String clePrivee, final String sujet) {
    this(clePublique, clePrivee, sujet, transportJdk());
  }

  /**
   * @param clePublique clé publique VAPID (point non compressé, base64url)
   * @param clePrivee clé privée VAPID (scalaire, base64url)
   * @param sujet identifiant {@code mailto:} ou {@code https:} requis par VAPID
   * @param transport envoi HTTP de la requête chiffrée
   * @throws IllegalArgumentException si les clés sont invalides
   */
  public ClientWebPush(
      final String clePublique,
      final String clePrivee,
      final String sujet,
      final Transport transport) {
    try {
      this.webPush = new WebPush(sujet, VapidKeys.fromUncompressedBytes(clePublique, clePrivee));
    } catch (RuntimeException e) {
      throw new IllegalArgumentException("Clés VAPID illisibles", e);
    }
    this.transport = transport;
  }

  /**
   * @param endpoint adresse d'abonnement chez le service push
   * @param p256dh clé publique de l'abonnement (base64 ou base64url)
   * @param auth secret d'authentification de l'abonnement (base64 ou base64url)
   * @param charge contenu de la notification
   * @return statut et début du corps de la réponse
   * @throws IOException si le service push est injoignable ou ne répond pas à temps
   * @throws InterruptedException si l'envoi est interrompu
   */
  public Reponse envoyer(
      final String endpoint, final String p256dh, final String auth, final String charge)
      throws IOException, InterruptedException {
    final byte[] corps =
        webPush.getBody(charge.getBytes(StandardCharsets.UTF_8), decoder(p256dh), decoder(auth));
    return transport.poster(endpoint, webPush.getHeaders(endpoint, null, null, null), corps);
  }

  private static Transport transportJdk() {
    final HttpClient http = HttpClient.newBuilder().connectTimeout(DELAI_CONNEXION).build();
    return (endpoint, entetes, corps) -> {
      final HttpRequest.Builder requete =
          HttpRequest.newBuilder(URI.create(endpoint))
              .timeout(DELAI_REPONSE)
              .POST(HttpRequest.BodyPublishers.ofByteArray(corps));
      entetes.forEach(requete::setHeader);
      final HttpResponse<byte[]> reponse =
          http.send(requete.build(), HttpResponse.BodyHandlers.ofByteArray());
      final byte[] brut = reponse.body();
      return new Reponse(
          reponse.statusCode(),
          new String(brut, 0, Math.min(brut.length, OCTETS_MAX_CORPS), StandardCharsets.UTF_8));
    };
  }

  // Les clés sont stockées en base64 standard ou URL selon le navigateur.
  private static byte[] decoder(final String valeur) {
    return Base64.getUrlDecoder().decode(valeur.replace('+', '-').replace('/', '_'));
  }
}
