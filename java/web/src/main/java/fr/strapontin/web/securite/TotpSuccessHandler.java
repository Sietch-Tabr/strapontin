/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.securite;

/**
 * Nom de l'attribut de session marquant un challenge TOTP en attente, partagé entre {@link
 * AuthController} (pose/efface l'attribut) et {@link TotpGuardFilter} (le vérifie).
 *
 * @since 2.0.0
 * @author Kwaite
 */
final class TotpSuccessHandler {

  /* package-private : réutilisé par AuthController/TotpGuardFilter (même package). */
  static final String SESSION_TOTP_EN_ATTENTE = "TOTP_EN_ATTENTE";

  private TotpSuccessHandler() {}
}
