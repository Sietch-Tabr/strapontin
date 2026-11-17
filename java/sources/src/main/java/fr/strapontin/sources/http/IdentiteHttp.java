/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.sources.http;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * Identité déclarée aux services externes (en-tête {@code User-Agent}) :
 * nom, version, adresse du projet et contact, définis une seule fois. Le
 * contact est relu à chaque appel, sans redémarrage.
 *
 * @since 3.1.5
 * @author Kwaite
 */
public final class IdentiteHttp {

  /** Adresse du dépôt, défaut de {@code strapontin.projet.url}. */
  public static final String URL_PROJET_PAR_DEFAUT = "https://github.com/Sietch-Tabr/strapontin";

  private static final String NOM = "Strapontin";

  private final String version;
  private final String adresseProjet;
  private final Supplier<String> contact;

  /**
   * @param version version courante de l'application
   * @param urlProjet adresse publique du projet
   * @param contact fournisseur du contact (e-mail), vide ou nul si absent
   */
  public IdentiteHttp(
      final String version, final String urlProjet, final Supplier<String> contact) {
    this.version = Objects.requireNonNull(version);
    this.adresseProjet = Objects.requireNonNull(urlProjet);
    this.contact = Objects.requireNonNull(contact);
  }

  /**
   * @return valeur du {@code User-Agent} : nom, version, adresse du projet
   *     et contact s'il est renseigné
   */
  public String userAgent() {
    final String adresse = contact.get();
    final String coordonnees =
        adresse == null || adresse.isBlank()
            ? adresseProjet
            : adresseProjet + "; " + adresse.strip();
    return NOM + "/" + version + " (" + coordonnees + ")";
  }

  /**
   * @return adresse publique du projet
   */
  public String urlProjet() {
    return adresseProjet;
  }
}
