/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.miseajour;

import fr.strapontin.exploitation.AppelInterne;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;

/**
 * Rapport envoyé à l'endpoint interne de l'application par HTTP local,
 * protégé par un secret partagé. Sans secret, rien n'est envoyé.
 *
 * @since 3.2.0
 * @author Kwaite
 */
public final class RapporteurHttp implements Rapporteur {

  private static final System.Logger JOURNAL = System.getLogger("miseajour");
  private static final Duration PAUSE = Duration.ofSeconds(3);
  private static final int TENTATIVES = 30;

  private final AppelInterne appel;
  private final URI adresse;
  private final String secret;

  /**
   * L'application redémarre pendant ou juste après une mise à jour (retour
   * arrière compris) : l'envoi est repris pendant environ une minute et demie.
   *
   * @param client client HTTP
   * @param adresse adresse de {@code /api/interne/mise-a-jour-statut}
   * @param secret valeur de {@code UPDATE_INTERNAL_SECRET}, vide si absente
   */
  public RapporteurHttp(final HttpClient client, final URI adresse, final String secret) {
    this(new AppelInterne(client, TENTATIVES, PAUSE), adresse, secret);
  }

  /* package-private : les tests réduisent le nombre d'essais et la pause. */
  RapporteurHttp(final AppelInterne appel, final URI adresse, final String secret) {
    this.appel = appel;
    this.adresse = adresse;
    this.secret = secret;
  }

  @Override
  public void envoyer(final boolean reussie, final String version, final String message) {
    if (secret == null || secret.isBlank()) {
      JOURNAL.log(
          System.Logger.Level.WARNING, "UPDATE_INTERNAL_SECRET absent : rapport non envoyé");
      return;
    }
    appel.poster(
        adresse,
        secret,
        Map.of("resultat", reussie ? "reussi" : "echec", "version", version, "message", message));
  }
}
