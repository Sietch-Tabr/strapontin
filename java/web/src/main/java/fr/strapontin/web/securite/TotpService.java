/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.securite;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.io.ByteArrayOutputStream;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import uk.org.okapibarcode.backend.QrCode;
import uk.org.okapibarcode.graphics.Color;
import uk.org.okapibarcode.output.SvgRenderer;

/**
 * Service TOTP (Time-based One-Time Password, RFC 6238) — implémentation Java pur.
 *
 * <p>Secret : 20 octets aléatoires encodés en Base32 (160 bits, conforme RFC 4648). Code :
 * HMAC-SHA1 tronqué à 6 chiffres toutes les 30 secondes. Vérification : tolérance ±1 fenêtre (30
 * s) pour la dérive d'horloge.
 *
 * @since 2.0.0
 * @author Kwaite
 */
@Service
public class TotpService {

  private static final Logger LOG = LoggerFactory.getLogger(TotpService.class);
  private static final String ISSUER = "Strapontin";
  private static final String BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
  private static final int TAILLE_SECRET_OCTETS = 20;
  private static final long PERIODE_SECONDES = 30L;
  private static final int DIGITS = 6;
  private static final int[] PUISSANCES_10 = {1, 10, 100, 1_000, 10_000, 100_000, 1_000_000};

  private final SecureRandom rng = new SecureRandom();
  private final Cache<String, Set<String>> codesConsommes =
      Caffeine.newBuilder().expireAfterWrite(90, TimeUnit.SECONDS).maximumSize(10_000).build();

  /**
   * Génère un nouveau secret TOTP encodé en Base32.
   *
   * @return le secret Base32 (32 caractères pour 20 octets)
   */
  public String genererSecret() {
    final byte[] octets = new byte[TAILLE_SECRET_OCTETS];
    rng.nextBytes(octets);
    return base32Encoder(octets);
  }

  /**
   * Construit l'URI {@code otpauth://} compatible Google Authenticator / Aegis.
   *
   * @param secret le secret Base32 du compte
   * @param identifiant le nom d'utilisateur affiché dans l'app TOTP
   * @return l'URI otpauth://totp/...
   */
  public String genererUriOtpAuth(final String secret, final String identifiant) {
    final String label = URLEncoder.encode("Strapontin:" + identifiant, StandardCharsets.UTF_8);
    final String issuer = URLEncoder.encode(ISSUER, StandardCharsets.UTF_8);
    return "otpauth://totp/"
        + label
        + "?secret="
        + secret
        + "&issuer="
        + issuer
        + "&algorithm=SHA1&digits=6&period=30";
  }

  /**
   * Génère un QR code SVG embarquable pour l'URI {@code otpauth://} fournie.
   *
   * @param otpUri l'URI otpauth:// à encoder
   * @return le contenu SVG (sans déclaration XML), ou {@code null} en cas d'erreur
   */
  public String genererQrCodeSvg(final String otpUri) {
    try {
      final QrCode qrCode = new QrCode();
      qrCode.setContent(otpUri);
      final ByteArrayOutputStream baos = new ByteArrayOutputStream();
      final SvgRenderer renderer = new SvgRenderer(baos, 3.0, Color.WHITE, Color.BLACK, false, 0);
      renderer.render(qrCode);
      return baos.toString(StandardCharsets.UTF_8);
    } catch (Exception e) {
      LOG.warn("Erreur génération QR code SVG TOTP", e);
      return null;
    }
  }

  /**
   * Vérifie un code TOTP saisi par l'utilisateur.
   *
   * <p>Accepte une dérive de ±1 fenêtre (30 s) pour la tolérance réseau/horloge. Un code valide ne
   * peut être accepté qu'une seule fois dans sa fenêtre (prévention du rejeu).
   *
   * @param secret le secret Base32 du compte
   * @param code le code à 6 chiffres saisi
   * @return {@code true} si le code est valide dans la fenêtre courante ou adjacente, et non déjà
   *   utilisé
   */
  public boolean verifierCode(final String secret, final String code) {
    if (secret == null || code == null) {
      return false;
    }
    // Retire tous les espaces (et non seulement aux extrémités) : les applications TOTP comme
    // Bitwarden affichent le code par groupes (« 365 493 »), souvent collé tel quel.
    final String codeNorm = code.replaceAll("\\s", "");
    if (!codeNorm.matches("\\d{6}")) {
      return false;
    }
    try {
      final byte[] secretOctets = base32Decoder(secret);
      final long compteur = System.currentTimeMillis() / 1000L / PERIODE_SECONDES;
      for (int derive = -1; derive <= 1; derive++) {
        final long pas = compteur + derive;
        if (MessageDigest.isEqual(
            calculerCode(secretOctets, pas).getBytes(StandardCharsets.UTF_8),
            codeNorm.getBytes(StandardCharsets.UTF_8))) {
          final String cleCache = secret + ":" + pas;
          final Set<String> utilises =
              codesConsommes.get(cleCache, _ -> ConcurrentHashMap.newKeySet());
          if (!utilises.add(codeNorm)) {
            return false;
          }
          return true;
        }
      }
      return false;
    } catch (Exception e) {
      LOG.warn("Erreur vérification code TOTP", e);
      return false;
    }
  }

  private String calculerCode(final byte[] secret, final long compteur)
      throws NoSuchAlgorithmException, InvalidKeyException {
    final byte[] msg = ByteBuffer.allocate(8).putLong(compteur).array();
    final Mac mac = Mac.getInstance("HmacSHA1");
    mac.init(new SecretKeySpec(secret, "HmacSHA1"));
    final byte[] hash = mac.doFinal(msg);
    final int offset = hash[hash.length - 1] & 0x0F;
    final int binaire =
        ((hash[offset] & 0x7F) << 24)
            | ((hash[offset + 1] & 0xFF) << 16)
            | ((hash[offset + 2] & 0xFF) << 8)
            | (hash[offset + 3] & 0xFF);
    final int otp = binaire % PUISSANCES_10[DIGITS];
    return String.format("%0" + DIGITS + "d", otp);
  }

  static String base32Encoder(final byte[] data) {
    final StringBuilder sb = new StringBuilder();
    int tampon = 0;
    int bitsRestants = 0;
    for (final byte b : data) {
      tampon = (tampon << 8) | (b & 0xFF);
      bitsRestants += 8;
      while (bitsRestants >= 5) {
        bitsRestants -= 5;
        sb.append(BASE32.charAt((tampon >> bitsRestants) & 0x1F));
      }
    }
    if (bitsRestants > 0) {
      sb.append(BASE32.charAt((tampon << (5 - bitsRestants)) & 0x1F));
    }
    return sb.toString();
  }

  static byte[] base32Decoder(final String encoded) {
    final String norm = encoded.toUpperCase().replaceAll("[^A-Z2-7]", "");
    final byte[] resultat = new byte[norm.length() * 5 / 8];
    int tampon = 0;
    int bitsRestants = 0;
    int idx = 0;
    for (final char c : norm.toCharArray()) {
      final int val = BASE32.indexOf(c);
      if (val < 0) {
        continue;
      }
      tampon = (tampon << 5) | val;
      bitsRestants += 5;
      if (bitsRestants >= 8) {
        bitsRestants -= 8;
        resultat[idx++] = (byte) (tampon >> bitsRestants);
      }
    }
    return resultat;
  }
}
