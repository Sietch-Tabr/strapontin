/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.securite;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.Objects;

/**
 * Clé de limitation de débit dérivée de l'adresse d'un client : une adresse
 * IPv6 est ramenée à son préfixe /64, car un client en contrôle toutes les
 * adresses et en changerait à chaque requête pour échapper à sa limite.
 * @since 3.0.6
 * @author Kwaite
 */
public final class AdresseClient {

  private static final String INCONNUE = "inconnu";
  private static final int OCTETS_PREFIXE = 8;

  private AdresseClient() {}

  /**
   * @param adresse adresse distante de la requête, éventuellement {@code null}
   * @return l'adresse IPv4 telle quelle, le préfixe /64 d'une adresse IPv6, ou
   *     {@code "inconnu"}
   */
  public static String cle(final String adresse) {
    if (adresse == null || adresse.isBlank()) {
      return INCONNUE;
    }
    final String brute = adresse.strip();
    // Sans deux-points ce n'est pas un littéral IPv6 : aucune résolution DNS n'est
    // tentée.
    if (!brute.contains(":")) {
      return brute;
    }
    try {
      final InetAddress resolue = InetAddress.getByName(brute);
      if (resolue instanceof Inet4Address) {
        return resolue.getHostAddress();
      }
      return resolue instanceof Inet6Address
          ? prefixe64(resolue.getAddress())
          : brute.toLowerCase(Locale.ROOT);
    } catch (UnknownHostException _) {
      // Littéral non analysable : la valeur brute sert de clé.
      return brute.toLowerCase(Locale.ROOT);
    }
  }

  private static String prefixe64(final byte[] octets) {
    Objects.requireNonNull(octets);
    final StringBuilder prefixe = new StringBuilder();
    for (int i = 0; i < OCTETS_PREFIXE; i += 2) {
      if (i > 0) {
        prefixe.append(':');
      }
      prefixe.append(String.format("%x", ((octets[i] & 0xff) << 8) | (octets[i + 1] & 0xff)));
    }
    return prefixe.append("::/64").toString();
  }
}
