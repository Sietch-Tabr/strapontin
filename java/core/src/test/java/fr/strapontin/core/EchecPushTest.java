/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class EchecPushTest {

  @Test
  void interpreter_statut2xx_retourneSucces() {
    assertEquals(EchecPush.Issue.SUCCES, EchecPush.interpreter(201));
  }

  @ParameterizedTest
  @ValueSource(ints = {404, 410})
  void interpreter_404Ou410_retourneAbonnementSupprime(final int statut) {
    assertEquals(EchecPush.Issue.ABONNEMENT_SUPPRIME, EchecPush.interpreter(statut));
  }

  @ParameterizedTest
  @ValueSource(ints = {400, 403, 413, 429, 500, 503})
  void interpreter_autreStatut_retourneEchec(final int statut) {
    assertEquals(EchecPush.Issue.ECHEC, EchecPush.interpreter(statut));
  }

  @Test
  void seuilAtteint_auCinquiemeEchec_retourneVrai() {
    assertFalse(EchecPush.seuilAtteint(4));
    assertTrue(EchecPush.seuilAtteint(5));
    assertFalse(EchecPush.seuilAtteint(null));
  }

  @Test
  void raison_corpsAvecUrlEtSautsDeLigne_retireLUrlEtCompacte() {
    assertEquals(
        "HTTP 403 : the VAPID credentials do not match [url]",
        EchecPush.raison(
            403, "the VAPID credentials\n  do not match https://fcm.googleapis.com/fcm/send/abc"));
  }

  @Test
  void raison_corpsTropLong_tronque() {
    final String raison = EchecPush.raison(500, "x".repeat(500));

    assertTrue(raison.endsWith("…"));
    assertTrue(raison.length() < 220);
  }

  @Test
  void raison_sansReponseNiCorps_retournePrefixeSeul() {
    assertEquals("sans réponse", EchecPush.raison(null, null));
  }

  @Test
  void masquerEndpoint_retireLeCheminIdentifiantLAppareil() {
    assertEquals(
        "https://fcm.googleapis.com/…",
        EchecPush.masquerEndpoint("https://fcm.googleapis.com/fcm/send/identifiant-secret"));
    assertEquals("(aucun)", EchecPush.masquerEndpoint(null));
  }

  @Test
  void causeLisible_403_mentionneLeRefusDuService() {
    assertTrue(EchecPush.causeLisible(403).contains("refuse"));
    assertTrue(EchecPush.causeLisible(null).contains("injoignable"));
  }

  @Test
  void lireCorps_fluxLisible_retourneLeTexte() {
    assertEquals(
        "refus",
        EchecPush.lireCorps(
            () -> new ByteArrayInputStream("refus".getBytes(StandardCharsets.UTF_8))));
  }

  @Test
  void lireCorps_fluxAbsentOuIllisible_retourneNull() {
    assertNull(EchecPush.lireCorps(() -> null));
    assertNull(
        EchecPush.lireCorps(
            () -> {
              throw new IOException("illisible");
            }));
  }
}
