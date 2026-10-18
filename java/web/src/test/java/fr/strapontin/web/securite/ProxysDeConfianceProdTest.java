/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.securite;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Vérifie que la prod ne fait confiance à X-Forwarded-For que du proxy inverse. */
class ProxysDeConfianceProdTest {

  private static Pattern proxys;

  @BeforeAll
  static void charger() throws IOException {
    final Properties proprietes = new Properties();
    try (InputStream flux =
        ProxysDeConfianceProdTest.class.getResourceAsStream("/application-prod.properties")) {
      assertNotNull(flux);
      proprietes.load(flux);
    }
    proxys = Pattern.compile(proprietes.getProperty("server.tomcat.remoteip.internal-proxies"));
  }

  @Test
  void proxysDeConfiance_proxyInverseEtBoucleLocale_acceptes() {
    assertTrue(proxys.matcher("192.168.20.98").matches());
    assertTrue(proxys.matcher("127.0.0.1").matches());
    assertTrue(proxys.matcher("0:0:0:0:0:0:0:1").matches());
  }

  @Test
  void proxysDeConfiance_autrePosteDuReseauLocal_refuse() {
    assertFalse(proxys.matcher("192.168.20.5").matches());
    assertFalse(proxys.matcher("192.168.10.98").matches());
    assertFalse(proxys.matcher("192.168.20.980").matches());
    assertFalse(proxys.matcher("10.0.0.1").matches());
  }
}
