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
 * Représentation Spring Security d'un utilisateur Strapontin
 * (détail : CLAUDE.md, Authentification), champs auto-descriptifs.
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
