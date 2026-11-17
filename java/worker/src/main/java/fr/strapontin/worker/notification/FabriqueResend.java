/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker.notification;

import com.resend.Resend;

/**
 * Fabrique du client Resend : indirection qui rend l'envoi d'email testable
 * sur son chemin nominal, le SDK Resend n'étant pas injectable directement.
 * @since 3.0.6
 * @author Kwaite
 */
/* package-private : fabrique interne substituable en test. */
@FunctionalInterface
interface FabriqueResend {

  /**
   * @param cle clé API Resend
   * @return client Resend prêt à l'emploi
   */
  Resend creer(String cle);

  /**
   * @return la fabrique de production
   */
  static FabriqueResend parDefaut() {
    return Resend::new;
  }
}
