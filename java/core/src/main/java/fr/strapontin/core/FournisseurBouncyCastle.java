/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.core;

import java.security.Security;
import org.bouncycastle.jce.provider.BouncyCastleProvider;

/**
 * Enregistrement du fournisseur cryptographique BouncyCastle, requis par
 * {@code web-push} pour charger les clés VAPID (fournisseur {@code "BC"}).
 * À appeler par tout composant qui envoie des notifications push, dans le
 * module web comme dans le worker : l'oubli ne se voit qu'à l'envoi.
 * @since 3.0.3
 * @author Kwaite
 */
public final class FournisseurBouncyCastle {

  private FournisseurBouncyCastle() {}

  /**
   * Enregistre BouncyCastle s'il ne l'est pas déjà : {@link Security#addProvider}
   * ignore un fournisseur du même nom déjà installé, l'appel est idempotent.
   */
  public static void garantir() {
    if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
      Security.addProvider(new BouncyCastleProvider());
    }
  }
}
