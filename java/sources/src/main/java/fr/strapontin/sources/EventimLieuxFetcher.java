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
import java.util.function.UnaryOperator;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Requêtes Eventim par lieu ({@code venue_names}) ou par villes ({@code
 * city_names}), seuls filtres géographiques appliqués par l'API, reprises
 * par fenêtre de dates au-delà du plafond de pages de l'API.
 * @since 3.0.1
 * @author Kwaite
 */
final class EventimLieuxFetcher {

  private static final Logger LOG = LoggerFactory.getLogger(EventimLieuxFetcher.class);

  private static final int NB_FENETRES_MAX = 20;
  // Au-delà de 10 valeurs city_names, l'API répond 400 « Invalid search parameter ».
  private static final int VILLES_PAR_REQUETE = 10;
  private static final int MOIS_HORIZON_SALLE = 24;
  /* package-private : jours toujours couverts par un fetch par lieu (24 mois). */
  static final int JOURS_COUVERTS_SALLE = 729;
  private static final int MOIS_HORIZON_REGION = 6;
  // Eventim classe une partie des lieux parisiens sous « Paris 07 »… sans slug :
  // city_names ne les atteint pas, seul postal_code (une valeur exacte) le fait.
  private static final String DEPT_PARIS = "75";
  private static final List<String> CODES_POSTAUX_PARIS =
      Stream.concat(
              IntStream.rangeClosed(1, 20).mapToObj(n -> String.format("750%02d", n)),
              Stream.of("75116"))
          .toList();
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
  /* package-private : instancié par EventimSource (même package). */
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

  private static LocalDate horizonSalle() {
    return LocalDate.now().plusMonths(MOIS_HORIZON_SALLE).minusDays(1);
  }

  /**
   * Concerts à venir d'un lieu Eventim, toutes catégories confondues.
   * @param libelle nom exact du lieu chez Eventim
   * @return concerts trouvés
   */
  /* package-private : appelé par LieuxEventim (même package). */
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
   * Concerts à venir des villes d'une région (Paris aussi par code postal).
   * @param villes villes à interroger (orthographe Eventim)
   * @param depts départements retenus (une ville homonyme d'un autre
   *     département est écartée)
   * @param categories catégories Eventim à inclure
   * @return concerts et dernière date couverte pour toutes les villes
   */
  /* package-private : appelé par LieuxEventim (même package). */
  ResultatRegion fetchVilles(
      final List<String> villes, final Set<String> depts, final List<String> categories) {
    final List<UnaryOperator<UriComponentsBuilder>> filtres = new ArrayList<>();
    for (int i = 0; i < villes.size(); i += VILLES_PAR_REQUETE) {
      final Object[] paquet =
          villes.subList(i, Math.min(i + VILLES_PAR_REQUETE, villes.size())).toArray();
      filtres.add(requete -> requete.queryParam("city_names", paquet));
    }
    if (depts.contains(DEPT_PARIS)) {
      CODES_POSTAUX_PARIS.forEach(
          code -> filtres.add(requete -> requete.queryParam("postal_code", code)));
    }
    final LocalDate fin = LocalDate.now().plusMonths(MOIS_HORIZON_REGION);
    final List<ConcertBrut> concerts = new ArrayList<>();
    LocalDate horizon = fin.minusDays(1);
    for (final UnaryOperator<UriComponentsBuilder> filtre : filtres) {
      final ResultatRegion resultat =
          parcourirFenetres(
              debut ->
                  filtre.apply(requete(debut, fin).queryParam("categories", categories.toArray())),
              fin,
              depts);
      concerts.addAll(resultat.concerts());
      horizon = plusProche(horizon, resultat.horizon());
    }
    return new ResultatRegion(concerts, horizon);
  }

  /* package-private : départements interrogés même sans ville connue. */
  static boolean interrogeableSansVille(final List<String> depts) {
    return depts.contains(DEPT_PARIS);
  }

  /**
   * Compte les concerts à venir d'un lieu (une seule page d'un résultat).
   * @param libelle nom exact du lieu chez Eventim
   * @return total annoncé par Eventim, ou -1 si la requête a échoué
   */
  /* package-private : appelé par LieuxEventim (même package). */
  int compterConcerts(final String libelle) {
    final LocalDate aujourdHui = LocalDate.now();
    final PageProduitsEventim page =
        chargeur.apply(
            requete(aujourdHui, aujourdHui.plusMonths(MOIS_HORIZON_SALLE))
                .queryParam("top", 1)
                .queryParam("venue_names", libelle)
                .queryParam("page", 1)
                .build()
                .encode()
                .toUri());
    return page.succes() ? page.total() : -1;
  }

  private static LocalDate plusProche(final LocalDate premiere, final LocalDate seconde) {
    if (premiere == null || seconde == null) {
      return null;
    }
    return premiere.isBefore(seconde) ? premiere : seconde;
  }

  private UriComponentsBuilder requete(final LocalDate debut, final LocalDate fin) {
    return UriComponentsBuilder.fromUriString(urlProduits)
        .queryParam("webId", webId)
        .queryParam("language", "fr")
        .queryParam("date_from", FORMAT_DATE.format(debut))
        .queryParam("date_to", FORMAT_DATE.format(fin))
        .queryParam("sort", "DateAsc");
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
    int totalPages = 0;
    for (int page = 1; page <= EventimSource.PLAFOND_PAGES; page++) {
      if (page > 1 && !attente.getAsBoolean()) {
        return new Parcours(concerts, false, false, null);
      }
      final PageProduitsEventim resultat =
          chargeur.apply(requete.cloneBuilder().queryParam("page", page).build().encode().toUri());
      if (!resultat.succes()) {
        return new Parcours(concerts, false, false, null);
      }
      concerts.addAll(resultat.concerts());
      totalPages = resultat.totalPages();
      if (resultat.estVide() || page >= totalPages) {
        break;
      }
    }
    return new Parcours(
        concerts, true, totalPages >= EventimSource.PLAFOND_PAGES, derniereDate(concerts));
  }


  private static LocalDate derniereDate(final List<ConcertBrut> concerts) {
    return concerts.stream()
        .map(concert -> LocalDate.parse(concert.date()))
        .max(LocalDate::compareTo)
        .orElse(null);
  }
}
