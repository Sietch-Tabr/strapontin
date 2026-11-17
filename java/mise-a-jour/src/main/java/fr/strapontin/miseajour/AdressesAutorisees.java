/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.miseajour;

import java.net.URI;
import java.util.Locale;
import java.util.Set;

/**
 * Contrôle des adresses joignables : schéma, absence d'identifiants et
 * hôte dans la liste autorisée, à chaque saut de redirection.
 *
 * @since 3.2.0
 * @author Kwaite
 */
public final class AdressesAutorisees {

  private static final int PORT_HTTPS = 443;

  private final Set<String> hotes;
  private final boolean httpsExige;

  /**
   * @param hotes hôtes autorisés
   * @param httpsExige vrai pour n'accepter que HTTPS sur son port usuel
   */
  public AdressesAutorisees(final Set<String> hotes, final boolean httpsExige) {
    this.hotes = Set.copyOf(hotes);
    this.httpsExige = httpsExige;
  }

  /**
   * Refuse une adresse qui sort du périmètre autorisé.
   *
   * @param adresse adresse à contrôler
   * @throws SourceInaccessibleException si l'adresse est refusée
   */
  public void verifier(final URI adresse) {
    final String hote = adresse.getHost() == null ? "" : adresse.getHost().toLowerCase(Locale.ROOT);
    if (!schemaValide(adresse) || adresse.getUserInfo() != null || !hotes.contains(hote)) {
      throw new SourceInaccessibleException("Adresse refusée : " + adresse.getHost());
    }
  }

  private boolean schemaValide(final URI adresse) {
    final boolean https = "https".equals(adresse.getScheme());
    if (httpsExige) {
      return https && (adresse.getPort() <= 0 || adresse.getPort() == PORT_HTTPS);
    }
    return https || "http".equals(adresse.getScheme());
  }
}
