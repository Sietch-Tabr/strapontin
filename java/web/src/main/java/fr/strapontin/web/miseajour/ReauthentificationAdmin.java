/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.miseajour;

import fr.strapontin.web.ErreurMetier;
import fr.strapontin.web.admin.LogAdminService;
import fr.strapontin.web.securite.LimiteurDebit;
import fr.strapontin.web.securite.TotpService;
import fr.strapontin.web.securite.UtilisateurDetails;
import fr.strapontin.web.utilisateur.UserController;
import java.time.Duration;
import java.util.Map;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Réauthentification de l'administrateur avant une opération exécutée en
 * root : mot de passe et code TOTP, vérifiés ensemble. Cinq échecs en quinze
 * minutes bloquent les essais, sans verrouiller le compte.
 *
 * @since 3.2.0
 * @author Kwaite
 */
@Component
public class ReauthentificationAdmin {

  private static final int MAX_ECHECS = 5;
  private static final Duration FENETRE = Duration.ofMinutes(15);

  private final PasswordEncoder passwordEncoder;
  private final TotpService totpService;
  private final UserController userController;
  private final LogAdminService logAdminService;
  private final LimiteurDebit echecs =
      new LimiteurDebit(MAX_ECHECS, FENETRE, FENETRE.plusMinutes(5), 1_000);

  /**
   * @param passwordEncoder vérification du mot de passe
   * @param totpService vérification du code TOTP
   * @param userController lecture du secret TOTP déchiffré
   * @param logAdminService journal d'administration
   */
  public ReauthentificationAdmin(
      final PasswordEncoder passwordEncoder,
      final TotpService totpService,
      final UserController userController,
      final LogAdminService logAdminService) {
    this.passwordEncoder = passwordEncoder;
    this.totpService = totpService;
    this.userController = userController;
    this.logAdminService = logAdminService;
  }

  /**
   * Indique si l'administrateur a activé son TOTP, lu en base :
   * la session est figée à la connexion.
   *
   * @param admin administrateur de la session
   * @return vrai si le TOTP est activé
   */
  public boolean totpActif(final UtilisateurDetails admin) {
    return userController.deuxFacteursActif(admin.id());
  }

  /**
   * Exige le mot de passe et le code TOTP de l'administrateur connecté.
   *
   * @param admin administrateur de la session
   * @param motDePasse mot de passe saisi
   * @param codeTotp code TOTP saisi
   * @throws ErreurMetier 422 TOTP absent ou faux, 429 trop d'échecs
   */
  public void exiger(
      final UtilisateurDetails admin, final String motDePasse, final String codeTotp) {
    if (!totpActif(admin)) {
      throw new ErreurMetier(
          "Activez d'abord l'authentification à deux facteurs de votre compte.", 422);
    }
    if (echecs.estEpuise(admin.id())) {
      throw new ErreurMetier("Trop de tentatives : réessayez dans quelques minutes.", 429);
    }
    final String secret = userController.lireSecretTotp(admin.id());
    // Les deux contrôles sont toujours faits : la réponse ne dit pas lequel est faux.
    final boolean motDePasseValide = passwordEncoder.matches(motDePasse, admin.motDePasse());
    final boolean totpValide = secret != null && totpService.verifierCode(secret, codeTotp);
    if (!motDePasseValide || !totpValide) {
      echecs.enregistrer(admin.id());
      logAdminService.logAdmin(
          "warn",
          "auth",
          "Réauthentification refusée pour la mise à jour : " + admin.identifiant(),
          Map.of("identifiant", admin.identifiant()));
      throw new ErreurMetier("Mot de passe ou code incorrect.", 422);
    }
    echecs.oublier(admin.id());
  }
}
