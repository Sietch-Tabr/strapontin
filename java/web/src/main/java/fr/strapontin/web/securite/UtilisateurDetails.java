/*
 * Strapontin — concert finder
 * Copyright (C) 2025 Kwaite
 */
package fr.strapontin.web.securite;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;

/// Représentation Spring Security d'un utilisateur Strapontin.
///
/// <p>{@link #isCredentialsNonExpired()} retourne {@code false} quand
/// {@code doitChangerCredentials} est vrai — Spring Security force alors le changement
/// de mot de passe à la connexion.
///
/// @param id                    l'identifiant unique de l'utilisateur
/// @param identifiant           le nom d'utilisateur (login, insensible à la casse)
/// @param email                 l'adresse email
/// @param motDePasse            le hash du mot de passe (bcrypt ou format Better-Auth)
/// @param role                  le rôle ({@code user} ou {@code admin})
/// @param twoFactorActif        si la 2FA TOTP est activée
/// @param doitChangerCredentials si l'utilisateur doit changer ses credentials
/// @since 2.0.0
/// @author Kwaite
public record UtilisateurDetails(
    String id,
    String identifiant,
    String email,
    String motDePasse,
    String role,
    boolean twoFactorActif,
    boolean doitChangerCredentials
) implements UserDetails {

  @Override
  public Collection<? extends GrantedAuthority> getAuthorities() {
    return List.of(new SimpleGrantedAuthority("ROLE_" + role.toUpperCase()));
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
    return !doitChangerCredentials;
  }
}
