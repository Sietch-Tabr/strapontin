/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.admin;

import fr.strapontin.web.config.ConfigService;
import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECPoint;
import java.util.Base64;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/// Service de gestion des clés VAPID pour les notifications push PWA.
///
/// <p>Génère une paire de clés EC P-256 (secp256r1) encodées en Base64 URL-safe,
/// les persiste dans {@code app_config} et invalide tous les abonnements push existants.
///
/// @since 2.2.0
/// @author Kwaite
@Service
public class VapidService {

  private static final Logger log = LoggerFactory.getLogger(VapidService.class);

  private final ConfigService configService;
  private final NamedParameterJdbcTemplate jdbc;
  private final LogAdminService logAdminService;

  /// @param configService   lecture/écriture de la configuration applicative
  /// @param jdbc            accès JDBC pour supprimer les abonnements push
  /// @param logAdminService audit des actions admin
  public VapidService(
      ConfigService configService,
      NamedParameterJdbcTemplate jdbc,
      LogAdminService logAdminService) {
    this.configService = configService;
    this.jdbc = jdbc;
    this.logAdminService = logAdminService;
  }

  /// Génère une paire de clés VAPID EC P-256, les persiste et supprime les abonnements push.
  ///
  /// @return paire de clés encodées en Base64 URL-safe et nombre d'abonnements supprimés
  /// @throws Exception si la génération de clés échoue
  public ResultatVapid genererVapid() throws Exception {
    KeyPairGenerator kpg = KeyPairGenerator.getInstance("EC");
    kpg.initialize(new ECGenParameterSpec("secp256r1"));
    KeyPair paire = kpg.generateKeyPair();

    ECPublicKey pubKey = (ECPublicKey) paire.getPublic();
    ECPoint w = pubKey.getW();
    byte[] uncompressed = new byte[65];
    uncompressed[0] = 0x04;
    byte[] x = vers32Octets(w.getAffineX());
    byte[] y = vers32Octets(w.getAffineY());
    System.arraycopy(x, 0, uncompressed, 1, 32);
    System.arraycopy(y, 0, uncompressed, 33, 32);
    String clePublique = Base64.getUrlEncoder().withoutPadding().encodeToString(uncompressed);

    ECPrivateKey privKey = (ECPrivateKey) paire.getPrivate();
    byte[] d = vers32Octets(privKey.getS());
    String clePrivee = Base64.getUrlEncoder().withoutPadding().encodeToString(d);

    configService.sauvegarderConfig("VAPID_PUBLIC_KEY", clePublique);
    configService.sauvegarderConfig("VAPID_PRIVATE_KEY", clePrivee);

    int nbSupprimes = jdbc.update("DELETE FROM push_abonnements", Map.of());
    logAdminService.logAdmin(
        "warn",
        "admin",
        "Clés VAPID régénérées — " + nbSupprimes + " abonnement(s) supprimé(s)",
        Map.of("nbAbonnements", nbSupprimes));

    return new ResultatVapid(clePublique, nbSupprimes);
  }

  private static byte[] vers32Octets(BigInteger n) {
    byte[] raw = n.toByteArray();
    if (raw.length == 32) return raw;
    byte[] result = new byte[32];
    if (raw.length > 32) {
      System.arraycopy(raw, raw.length - 32, result, 0, 32);
    } else {
      System.arraycopy(raw, 0, result, 32 - raw.length, raw.length);
    }
    return result;
  }

  /// Résultat de la génération des clés VAPID.
  ///
  /// @param clePublique             clé publique EC P-256 en Base64 URL-safe
  /// @param nbAbonnementsSupprimes  nombre d'abonnements push invalidés
  public record ResultatVapid(String clePublique, int nbAbonnementsSupprimes) {}
}
