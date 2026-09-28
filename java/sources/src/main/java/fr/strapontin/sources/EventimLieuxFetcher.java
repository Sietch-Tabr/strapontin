/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.sources;

import fr.strapontin.core.ConcertBrut;
import java.net.URI;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Récupération Eventim par lieu ({@code venue_names}) ou par villes ({@code
 * city_names}), seuls filtres géographiques réellement appliqués par l'API.
 * Une requête ne dépasse jamais {@value #MAX_PAGES} pages : au-delà, elle
 * reprend par fenêtre de dates plutôt que de s'arrêter en cours de route.
 *
 * @since 3.0.1
 * @author Kwaite
 */
final class EventimLieuxFetcher {

  private static final Logger LOG = LoggerFactory.getLogger(EventimLieuxFetcher.class);

  private static final int TAILLE_PAGE = 50;
  private static final int MAX_PAGES = 10;
  private static final int NB_FENETRES_MAX = 20;
  // Une URL trop longue est rejetée : les villes sont interrogées par paquets.
  private static final int VILLES_PAR_REQUETE = 40;
  private static final int MOIS_HORIZON_SALLE = 24;
  private static final int MOIS_HORIZON_REGION = 6;
  private static final DateTimeFormatter FORMAT_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");

  private final String urlProduits;
  private final String webId;
  private final Function<URI, PageProduitsEventim> chargeur;
  private final BooleanSupplier attente;

  /**
   * @param urlProduits URL de l'endpoint produits
   * @param webId identifiant du site Eventim
   * @param chargeur chargement d'une page (sans filtre de département)
   * @param attente pause entre deux pages, {@code false} si interrompue
   */
  EventimLieuxFetcher(
      final String urlProduits,
      final String webId,
      final Function<URI, PageProduitsEventim> chargeur,
      final BooleanSupplier attente) {
    this.urlProduits = urlProduits;
    this.webId = webId;
    this.chargeur = chargeur;
    this.attente = attente;
  }

  /**
   * Dernière date garantie couverte par {@link #fetchLieu}.
   * @return horizon de couverture d'un fetch par salle
   */
  static LocalDate horizonSalle() {
    return LocalDate.now().plusMonths(MOIS_HORIZON_SALLE).minusDays(1);
  }

  /**
   * Concerts à venir d'un lieu Eventim, toutes catégories confondues.
   * @param libelle nom exact du lieu chez Eventim
   * @return concerts trouvés
   */
  List<ConcertBrut> fetchLieu(final String libelle) {
    final LocalDate fin = LocalDate.now().plusMonths(MOIS_HORIZON_SALLE);
    final ResultatRegion resultat =
        parcourirFenetres(
            debut -> requete(debut, fin).queryParam("venue_names", libelle), fin, Set.of());
    if (resultat.horizon() == null || resultat.horizon().isBefore(horizonSalle())) {
      LOG.warn(
          "Eventim lieu={} : couverture tronquée au {} (volume hors norme)",
          libelle,
          resultat.horizon());
    }
    return resultat.concerts();
  }

  /**
   * Concerts à venir des villes d'une région, restreints à ses départements.
   * @param villes villes à interroger (orthographe Eventim)
   * @param depts départements retenus (une ville homonyme d'un autre
   *     département est écartée)
   * @param categories catégories Eventim à inclure
   * @return concerts et dernière date couverte pour toutes les villes
   */
  ResultatRegion fetchVilles(
      final List<String> villes, final Set<String> depts, final List<String> categories) {
    final LocalDate fin = LocalDate.now().plusMonths(MOIS_HORIZON_REGION);
    final List<ConcertBrut> concerts = new ArrayList<>();
    LocalDate horizon = fin.minusDays(1);
    for (int i = 0; i < villes.size(); i += VILLES_PAR_REQUETE) {
      final List<String> paquet =
          villes.subList(i, Math.min(i + VILLES_PAR_REQUETE, villes.size()));
      final ResultatRegion resultat =
          parcourirFenetres(
              debut ->
                  requete(debut, fin)
                      .queryParam("city_names", paquet.toArray())
                      .queryParam("categories", categories.toArray()),
              fin,
              depts);
      concerts.addAll(resultat.concerts());
      horizon = plusProche(horizon, resultat.horizon());
    }
    return new ResultatRegion(concerts, horizon);
  }

  private static LocalDate plusProche(final LocalDate a, final LocalDate b) {
    if (a == null || b == null) {
      return null;
    }
    return a.isBefore(b) ? a : b;
  }

  private UriComponentsBuilder requete(final LocalDate debut, final LocalDate fin) {
    return UriComponentsBuilder.fromUriString(urlProduits)
        .queryParam("webId", webId)
        .queryParam("language", "fr")
        .queryParam("date_from", FORMAT_DATE.format(debut))
        .queryParam("date_to", FORMAT_DATE.format(fin))
        .queryParam("sort", "DateAsc")
        .queryParam("top", TAILLE_PAGE);
  }

  /**
   * Parcourt une requête par fenêtres de dates successives jusqu'à épuisement.
   * @return concerts, et dernière date couverte (null si aucune garantie)
   */
  private ResultatRegion parcourirFenetres(
      final Function<LocalDate, UriComponentsBuilder> requeteDepuis,
      final LocalDate fin,
      final Set<String> depts) {
    final List<ConcertBrut> concerts = new ArrayList<>();
    LocalDate debut = LocalDate.now();
    for (int fenetre = 0; fenetre < NB_FENETRES_MAX; fenetre++) {
      final Parcours parcours = parcourirPages(requeteDepuis.apply(debut));
      parcours.concerts().stream()
          .filter(c -> depts.isEmpty() || depts.contains(c.dept()))
          .forEach(concerts::add);
      if (!parcours.succes()) {
        return new ResultatRegion(concerts, null);
      }
      if (!parcours.plafonne()) {
        return new ResultatRegion(concerts, fin.minusDays(1));
      }
      // Reprise au dernier jour reçu, partiellement couvert ; les doublons sont
      // dédoublonnés en aval.
      final LocalDate derniere = parcours.derniereDate();
      if (derniere == null || !derniere.isAfter(debut)) {
        return new ResultatRegion(concerts, derniere != null ? derniere.minusDays(1) : null);
      }
      debut = derniere;
    }
    return new ResultatRegion(concerts, debut.minusDays(1));
  }

  private record Parcours(
      List<ConcertBrut> concerts, boolean succes, boolean plafonne, LocalDate derniereDate) {}

  private Parcours parcourirPages(final UriComponentsBuilder requete) {
    final List<ConcertBrut> concerts = new ArrayList<>();
    boolean succes = true;
    boolean continuer = true;
    int total = 0;
    for (int page = 1; page <= MAX_PAGES && continuer; page++) {
      if (page > 1 && !attente.getAsBoolean()) {
        return new Parcours(concerts, false, false, null);
      }
      final PageProduitsEventim resultat =
          chargeur.apply(requete.cloneBuilder().queryParam("page", page).build().encode().toUri());
      succes &= resultat.succes();
      concerts.addAll(resultat.concerts());
      total = resultat.total();
      continuer = resultat.succes() && !resultat.estVide() && concerts.size() < total;
    }
    LocalDate derniere = null;
    for (final ConcertBrut concert : concerts) {
      final LocalDate date = LocalDate.parse(concert.date());
      if (derniere == null || date.isAfter(derniere)) {
        derniere = date;
      }
    }
    return new Parcours(concerts, succes, succes && concerts.size() < total, derniere);
  }
}
