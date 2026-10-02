/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.core;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Tests unitaires de {@link EndpointPush}.
 *
 * @since 3.0.2
 * @author Kwaite
 */
class EndpointPushTest {

  @ParameterizedTest
  @ValueSource(
      strings = {
        "https://fcm.googleapis.com/fcm/send/abc:def",
        "https://updates.push.services.mozilla.com/wpush/v2/gAAAA",
        "https://web.push.apple.com/QO1dK",
        "https://wns2-par02p.notify.windows.com/w/?token=abc",
        "https://FCM.googleapis.com:443/fcm/send/abc"
      })
  void estAutorise_serviceDePushDeNavigateur_accepte(final String endpoint) {
    assertTrue(EndpointPush.estAutorise(endpoint));
  }

  @ParameterizedTest
  @NullSource
  @ValueSource(
      strings = {
        "http://127.0.0.1:8081/trigger/tout",
        "http://localhost:8081/trigger/job?typeJob=purger:concerts",
        "https://127.0.0.1/push",
        "https://192.168.20.79:9090/actuator",
        "http://fcm.googleapis.com/fcm/send/abc",
        "https://fcm.googleapis.com:8443/fcm/send/abc",
        "https://fcm.googleapis.com.attaquant.fr/push",
        "https://attaquantfcm.googleapis.com.evil/push",
        "https://user:mdp@fcm.googleapis.com/fcm/send/abc",
        "https://evilfcm.googleapis.com/push",
        "file:///etc/passwd",
        "pas une url",
        ""
      })
  void estAutorise_adresseInterneOuHorsServicesDePush_refuse(final String endpoint) {
    assertFalse(EndpointPush.estAutorise(endpoint));
  }
}
