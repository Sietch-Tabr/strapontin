/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.securite;

import java.io.Serial;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

/**
 * Représentation Spring Security d'un utilisateur Strapontin.
 *
 * <p>{@link #isCredentialsNonExpired()} retourne toujours {@code true} : le forçage du changement
 * de mot de passe pour un compte invité ({@code doitChangerCredentials}) n'est pas géré par ce mécanisme
 * Spring Security mais explicitement par {@code AuthController}.
 *
 * @param id l'identifiant unique de l'utilisateur
 * @param identifiant le nom d'utilisateur (login, insensible à la casse)
 * @param email l'adresse email
 * @param motDePasse le hash du mot de passe (bcrypt ou format Better-Auth)
 * @param role le rôle ({@code user} ou {@code admin})
 * @param twoFactorActif si la 2FA TOTP est activée
 * @param doitChangerCredentials si l'utilisateur doit changer ses credentials
 * @since 2.0.0
 * @author Kwaite
 */
public record UtilisateurDetails(
    String id,
    String identifiant,
    String email,
    String motDePasse,
    String role,
    boolean twoFactorActif,
    boolean doitChangerCredentials)
    implements UserDetails {

  @Serial private static final long serialVersionUID = 1L;

  @Override
  public Collection<? extends GrantedAuthority> getAuthorities() {
    return List.of(new SimpleGrantedAuthority("ROLE_" + role.toUpperCase(Locale.ROOT)));
  }

  @Override
  public String getPassword() {
    return motDePasse;
  }

  @Override
  public String getUsername() {
    return identifiant;
  }

  @Override
  public boolean isCredentialsNonExpired() {
    return true;
  }
}
