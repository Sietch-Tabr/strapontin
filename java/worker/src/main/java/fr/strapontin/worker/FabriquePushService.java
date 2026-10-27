/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker;

import fr.strapontin.core.ClientWebPush;

/**
 * Fabrique du client push VAPID, même motif que {@link FabriqueResend}.
 * @since 3.0.6
 * @author Kwaite
 */
/* package-private : fabrique interne substituable en test. */
@FunctionalInterface
interface FabriquePushService {

  /**
   * @param clePublique clé publique VAPID
   * @param clePrivee clé privée VAPID
   * @param sujet identifiant {@code mailto:} ou {@code https:} requis par VAPID
   * @return client push prêt à l'emploi
   * @throws IllegalArgumentException si les clés sont invalides
   */
  ClientWebPush creer(String clePublique, String clePrivee, String sujet);

  /**
   * @return la fabrique de production
   */
  static FabriquePushService parDefaut() {
    return ClientWebPush::new;
  }
}
