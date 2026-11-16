/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.sources.http;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * Identité que l'application déclare aux services externes (en-tête {@code User-Agent}) : nom,
 * version courante, adresse du projet et contact. Une seule définition pour tous les clients ; le
 * contact est relu à chaque appel, une modification en administration s'applique sans
 * redémarrage.
 *
 * @since 3.1.5
 * @author Kwaite
 */
public final class IdentiteHttp {

  /** Adresse du dépôt public, valeur par défaut de la propriété {@code strapontin.projet.url}. */
  public static final String URL_PROJET_PAR_DEFAUT = "https://codeberg.org/Kwaite/strapontin";

  private static final String NOM = "Strapontin";

  private final String version;
  private final String urlProjet;
  private final Supplier<String> contact;

  /**
   * @param version version courante de l'application
   * @param urlProjet adresse publique du projet
   * @param contact fournisseur du contact (adresse e-mail), vide ou {@code null} s'il est absent
   */
  public IdentiteHttp(
      final String version, final String urlProjet, final Supplier<String> contact) {
    this.version = Objects.requireNonNull(version);
    this.urlProjet = Objects.requireNonNull(urlProjet);
    this.contact = Objects.requireNonNull(contact);
  }

  /**
   * @return valeur de l'en-tête {@code User-Agent} : nom, version, adresse du projet et contact
   *     quand il est renseigné
   */
  public String userAgent() {
    final String adresse = contact.get();
    final String coordonnees =
        adresse == null || adresse.isBlank() ? urlProjet : urlProjet + "; " + adresse.strip();
    return NOM + "/" + version + " (" + coordonnees + ")";
  }

  /**
   * @return adresse publique du projet
   */
  public String urlProjet() {
    return urlProjet;
  }
}
