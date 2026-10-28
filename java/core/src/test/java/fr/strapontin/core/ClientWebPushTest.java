/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.interaso.webpush.VapidKeys;
import java.math.BigInteger;
import java.security.SecureRandom;
import java.security.interfaces.ECPrivateKey;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Requête Web Push produite avec de vraies clés VAPID et un vrai abonnement : en-têtes attendus
 * par les services push, aucun en-tête interdit au client HTTP du JDK, contenu chiffré.
 *
 * @since 3.1.1
 * @author Kwaite
 */
class ClientWebPushTest {

  private static final Set<String> ENTETES_INTERDITS_JDK =
      Set.of("connection", "content-length", "expect", "host", "upgrade");

  private static String base64Url(final byte[] octets) {
    return Base64.getUrlEncoder().withoutPadding().encodeToString(octets);
  }

  // Clé privée au format stocké par l'application : scalaire brut sur 32 octets.
  private static String scalaire(final ECPrivateKey cle) {
    final byte[] brut = cle.getS().toByteArray();
    final byte[] sur32 = new byte[32];
    final int copie = Math.min(brut.length, 32);
    System.arraycopy(brut, brut.length - copie, sur32, 32 - copie, copie);
    return base64Url(sur32);
  }

  private record Envoi(Map<String, String> entetes, byte[] corps) {}

  @Test
  void envoyer_vraiesClesEtAbonnement_requeteAes128gcmSigneeSansEnteteInterdit() throws Exception {
    final VapidKeys vapid = VapidKeys.generate();
    final VapidKeys navigateur = VapidKeys.generate();
    final byte[] secret = new byte[16];
    new SecureRandom().nextBytes(secret);
    final List<Envoi> envois = new ArrayList<>();
    final ClientWebPush client =
        new ClientWebPush(
            base64Url(vapid.getApplicationServerKey()),
            scalaire(vapid.getPrivateKey()),
            "mailto:contact@exemple.fr",
            (endpoint, entetes, corps) -> {
              envois.add(new Envoi(entetes, corps));
              return new ClientWebPush.Reponse(201, "");
            });

    final ClientWebPush.Reponse reponse =
        client.envoyer(
            "https://fcm.googleapis.com/fcm/send/appareil",
            base64Url(navigateur.getApplicationServerKey()),
            base64Url(secret),
            "{\"title\":\"Strapontin\"}");

    assertEquals(201, reponse.statut());
    final Envoi envoi = envois.get(0);
    assertEquals("aes128gcm", envoi.entetes().get("Content-Encoding"));
    assertTrue(envoi.entetes().get("Authorization").startsWith("vapid t="));
    assertTrue(envoi.entetes().get("Authorization").contains(", k="));
    for (final String nom : envoi.entetes().keySet()) {
      assertFalse(ENTETES_INTERDITS_JDK.contains(nom.toLowerCase()), nom);
    }
    // En-tête RFC 8188 (sel 16 octets, taille d'enregistrement, clé publique) puis contenu.
    assertTrue(envoi.corps().length > 86 + 16);
    assertFalse(new String(envoi.corps()).contains("Strapontin"));
    assertTrue(new BigInteger(1, envoi.corps()).signum() > 0);
  }

  @Test
  void construire_sujetSansAdresse_leveIllegalArgumentException() {
    final VapidKeys vapid = VapidKeys.generate();
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ClientWebPush(
                base64Url(vapid.getApplicationServerKey()),
                scalaire(vapid.getPrivateKey()),
                "mailto:"));
  }

  @Test
  void construire_clesInvalides_leveIllegalArgumentException() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new ClientWebPush("pas-une-cle", "pas-une-cle", "mailto:contact@exemple.fr"));
  }
}
