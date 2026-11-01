/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.sources.ticketmaster;

import fr.strapontin.core.ConcertBrut;
import fr.strapontin.sources.CentreDept;
import fr.strapontin.sources.CentresDepartement;
import fr.strapontin.sources.ResultatRegion;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Récupération des concerts par région (géolocalisation centre de
 * département) — extrait de {@link TicketmasterSource} pour rester sous
 * le seuil de couplage (20), sans état propre (méthodes statiques).
 * @since 2.9.1
 * @author Kwaite
 */
final class TicketmasterRegionFetcher {

  private static final Logger LOG = LoggerFactory.getLogger(TicketmasterRegionFetcher.class);

  // Garde-fou contre une boucle sans fin (plus de 1000 concerts sur une seule
  // journée) : au-delà, l'horizon s'arrête à la dernière fenêtre complète.
  private static final int NB_FENETRES_MAX = 15;

  private TicketmasterRegionFetcher() {}

  /**
   * Concerts par région, par fenêtres de dates successives (TM refuse toute
   * page au-delà du 1000e résultat : une requête ne couvre sinon que les
   * premières semaines d'un département dense). Horizon null si un
   * département est ignoré.
   */
  /* package-private : appelé par TicketmasterSource (même package). */
  static ResultatRegion fetchRegion(
      final TicketmasterPageFetcher pageFetcher,
      final List<String> depts,
      final List<String> classifs) {
    final List<String> classifsEffectives =
        classifs == null ? TicketmasterSource.CLASSIFICATIONS : classifs;
    final List<ConcertBrut> tous = new ArrayList<>();
    LocalDate horizon = FenetreTempsTM.derniereDateCouverte();
    boolean departementIgnore = false;

    for (final String dept : depts) {
      final CentreDept centre = CentresDepartement.PAR_DEPT.get(dept);
      if (centre == null) {
        LOG.warn("département sans coordonnées connues, ignoré dept={}", dept);
        departementIgnore = true;
      } else {
        final ResultatRegion resultatDept =
            fetchDept(pageFetcher, dept, centre, classifsEffectives);
        tous.addAll(resultatDept.concerts());
        horizon = plusProche(horizon, resultatDept.horizon());
      }
    }

    return new ResultatRegion(tous, departementIgnore ? null : horizon);
  }

  private static LocalDate plusProche(final LocalDate premiere, final LocalDate seconde) {
    if (premiere == null || seconde == null) {
      return null;
    }
    return premiere.isBefore(seconde) ? premiere : seconde;
  }

  private static ResultatRegion fetchDept(
      final TicketmasterPageFetcher pageFetcher,
      final String dept,
      final CentreDept centre,
      final List<String> classifs) {
    final FenetreTempsTM fenetre = FenetreTempsTM.obtenirFenetreTemps();
    final LocalDate finCouverte = FenetreTempsTM.derniereDateCouverte();
    final List<ConcertBrut> concerts = new ArrayList<>();
    String debut = fenetre.debut();
    LocalDate debutFenetre = null;

    for (int fenetreNum = 0; fenetreNum < NB_FENETRES_MAX; fenetreNum++) {
      final UriComponentsBuilder uriBase =
          UriComponentsBuilder.fromUriString(
                  TicketmasterSource.BASE_URL + TicketmasterSource.CHEMIN_EVENTS)
              .queryParam("latlong", centre.lat() + "," + centre.lng())
              .queryParam("radius", centre.rayon())
              .queryParam("unit", "km")
              .queryParam("classificationName", classifs.toArray())
              .queryParam(TicketmasterSource.PARAM_SIZE, TicketmasterSource.TAILLE_PAGE_REGION)
              .queryParam(TicketmasterSource.PARAM_SORT, TicketmasterSource.TRI_DATE_ASC)
              .queryParam(TicketmasterSource.PARAM_START_DATE_TIME, debut)
              .queryParam("endDateTime", fenetre.fin());
      final TicketmasterPageFetcher.ResultatParcours parcours =
          pageFetcher.parcourirPages(uriBase, null);
      concerts.addAll(parcours.concerts());
      // Page en échec (quota, erreur HTTP) : couvert seulement jusqu'aux dates
      // reçues, sinon tout concert du département gagnait une absence.
      if (!parcours.succes()) {
        LOG.warn("fetch région TM dept={} : page en échec, couverture partielle", dept);
        return new ResultatRegion(concerts, couvertureRecue(concerts));
      }
      if (!parcours.plafonne()) {
        return new ResultatRegion(concerts, finCouverte);
      }

      // Reprise la veille de la dernière date reçue : ConcertBrut porte une date
      // locale, la borne TM est en UTC ; les doublons sont dédoublonnés en aval.
      final LocalDate derniere = derniereDate(parcours.concerts());
      final LocalDate reprise = derniere != null ? derniere.minusDays(1) : null;
      if (reprise == null || (debutFenetre != null && !reprise.isAfter(debutFenetre))) {
        pageFetcher.signalerPlafond(
            "dept=" + dept, parcours.totalElements(), parcours.concerts().size());
        return new ResultatRegion(concerts, derniere != null ? derniere.minusDays(2) : null);
      }
      debutFenetre = reprise;
      debut = FenetreTempsTM.debutDeJournee(reprise);
    }

    LOG.warn("fetch région TM dept={} : {} fenêtres atteintes", dept, NB_FENETRES_MAX);
    return new ResultatRegion(concerts, couvertureRecue(concerts));
  }

  // Dernière date reçue, moins une marge : la borne TM est en UTC.
  private static LocalDate couvertureRecue(final List<ConcertBrut> concerts) {
    final LocalDate derniere = derniereDate(concerts);
    return derniere != null ? derniere.minusDays(2) : null;
  }

  private static LocalDate derniereDate(final List<ConcertBrut> concerts) {
    LocalDate max = null;
    for (final ConcertBrut concert : concerts) {
      if (concert.date() != null) {
        final LocalDate date = LocalDate.parse(concert.date());
        if (max == null || date.isAfter(max)) {
          max = date;
        }
      }
    }
    return max;
  }
}
