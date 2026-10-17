/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.sources;

import fr.strapontin.core.BilanFetch;
import fr.strapontin.core.CompteurBilanFetch;
import fr.strapontin.core.ConcertBrut;
import fr.strapontin.core.PositionLieu;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;

/**
 * Source de concerts lue sur le site d'une salle qui vend hors Ticketmaster et Eventim : une
 * classe par site, une lecture par jour, jamais à la demande d'un utilisateur. Ajouter un site =
 * une sous-classe et un bean Spring.
 * @since 3.0.6
 * @author Kwaite
 */
public abstract class SourceSiteSalle implements SourceConcerts, SourceAvecBilanFetch {

  /** Valeur de {@code observations.source} et préfixe des identifiants de salle de ces sites. */
  public static final String SOURCE = "site_salle";

  private static final DateTimeFormatter HEURE = DateTimeFormatter.ofPattern("HH:mm");

  private final ClientSiteSalle client;
  private final CompteurBilanFetch compteurBilan = new CompteurBilanFetch();

  /**
   * @param client lecteur de pages (cadence et robots.txt respectés)
   */
  protected SourceSiteSalle(final ClientSiteSalle client) {
    this.client = Objects.requireNonNull(client, "client");
  }

  /**
   * @return identifiant court du site, sans espace ni deux-points (ex. {@code "triton"})
   */
  public abstract String identifiantSite();

  /**
   * Lit les pages du site et en extrait les concerts à venir.
   * @param lecteur lecteur de pages du site
   * @return les concerts annoncés, vide si le site n'en annonce aucun
   */
  protected abstract List<ConcertBrut> collecter(LecteurPages lecteur);

  /** Lecture d'une page du site, comptée dans le bilan de la source. */
  @FunctionalInterface
  public interface LecteurPages {

    /**
     * @param adresse adresse absolue de la page
     * @return le corps de la réponse
     */
    String lire(String adresse);
  }

  @Override
  public String nom() {
    return "site-" + identifiantSite();
  }

  /**
   * Lit le site : tous les concerts qu'il annonce, ou une exception si la lecture a échoué (un
   * concert attendu et non reçu n'est alors pas absent).
   * @return les concerts annoncés par le site
   */
  public final List<ConcertBrut> lireProgrammation() {
    try {
      return collecter(
          adresse -> {
            compteurBilan.enregistrerAppel();
            return client.lire(adresse);
          });
    } catch (RuntimeException e) {
      compteurBilan.enregistrerErreur(CauseEchecHttp.depuis(e));
      throw e;
    }
  }

  @Override
  public BilanFetch preleverBilan() {
    return compteurBilan.preleverBilan();
  }

  /**
   * @param lieu salle de l'événement
   * @param identifiantSalle identifiant de la salle chez ce site (sans préfixe)
   * @param artiste nom d'artiste ou d'affiche
   * @param date jour du concert
   * @param heure heure de début, null si le site n'en donne pas
   * @param url page de l'événement ou de sa billetterie
   * @return le concert brut, rattaché à la salle du site
   */
  protected static ConcertBrut concert(
      final LieuSite lieu,
      final String identifiantSalle,
      final String artiste,
      final LocalDate date,
      final LocalTime heure,
      final String url) {
    return concert(
        lieu,
        identifiantSalle,
        artiste,
        date,
        heure,
        url,
        new PositionLieu(null, null, lieu.codePostal()));
  }

  /**
   * Comme {@link #concert(LieuSite, String, String, LocalDate, LocalTime, String)}, avec la
   * position que le site publie pour la salle.
   * @param lieu salle de l'événement
   * @param identifiantSalle identifiant de la salle chez ce site (sans préfixe)
   * @param artiste nom d'artiste ou d'affiche
   * @param date jour du concert
   * @param heure heure de début, null si le site n'en donne pas
   * @param url page de l'événement ou de sa billetterie
   * @param position position publiée de la salle
   * @return le concert brut, rattaché à la salle du site
   */
  protected static ConcertBrut concert(
      final LieuSite lieu,
      final String identifiantSalle,
      final String artiste,
      final LocalDate date,
      final LocalTime heure,
      final String url,
      final PositionLieu position) {
    return ConcertBrut.nouveau()
        .artiste(artiste)
        .date(date.toString())
        .heure(heure == null ? null : HEURE.format(heure))
        .salle(lieu.nom())
        .ville(lieu.ville())
        .dept(lieu.dept())
        .position(position)
        .salleSourceId(SOURCE + ":" + identifiantSalle)
        .url(url)
        .source(SOURCE)
        .classification("music")
        .construire();
  }
}
