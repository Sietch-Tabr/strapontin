/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.sources;

import fr.strapontin.core.ConcertBrut;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Récupération des concerts par région (géolocalisation centre de département).
 *
 * <p>Extrait de {@link TicketmasterSource} pour rester sous le seuil de couplage entre objets (20)
 * — sans état propre (méthodes statiques), pour ne pas introduire de nouveau champ déclaré dans
 * {@link TicketmasterSource}.
 *
 * @since 2.9.1
 * @author Kwaite
 */
final class TicketmasterRegionFetcher {

  private static final Logger LOG = LoggerFactory.getLogger(TicketmasterRegionFetcher.class);

  private TicketmasterRegionFetcher() {}

  /**
   * Récupère les concerts par région (géolocalisation centre de département).
   *
   * @param pageFetcher pagination bas niveau partagée avec {@link TicketmasterSource}
   * @param depts liste de codes département (ex: ["75", "92"])
   * @param classifs classifications TM à inclure ; par défaut music, opera, musical
   * @return liste des concerts trouvés pour tous les départements
   */
  static List<ConcertBrut> fetchRegion(
      final TicketmasterPageFetcher pageFetcher,
      final List<String> depts,
      final List<String> classifs) {
    final List<String> classifsEffectives =
        classifs == null ? TicketmasterSource.CLASSIFICATIONS : classifs;
    final List<ConcertBrut> tous = new ArrayList<>();

    for (final String dept : depts) {
      final CentreDept centre = CentresDepartement.PAR_DEPT.get(dept);
      if (centre == null) {
        LOG.warn("département sans coordonnées connues, ignoré dept={}", dept);
      } else {
        tous.addAll(fetchDept(pageFetcher, dept, centre, classifsEffectives));
      }
    }

    return tous;
  }

  private static List<ConcertBrut> fetchDept(
      final TicketmasterPageFetcher pageFetcher,
      final String dept,
      final CentreDept centre,
      final List<String> classifs) {
    final FenetreTempsTM fenetre = FenetreTempsTM.obtenirFenetreTemps();
    final UriComponentsBuilder uriBase =
        UriComponentsBuilder.fromUriString(
                TicketmasterSource.BASE_URL + TicketmasterSource.CHEMIN_EVENTS)
            .queryParam("latlong", centre.lat() + "," + centre.lng())
            .queryParam("radius", centre.rayon())
            .queryParam("unit", "km")
            .queryParam("classificationName", classifs.toArray())
            .queryParam(TicketmasterSource.PARAM_SIZE, TicketmasterSource.TAILLE_PAGE_REGION)
            .queryParam(TicketmasterSource.PARAM_SORT, TicketmasterSource.TRI_DATE_ASC)
            .queryParam(TicketmasterSource.PARAM_START_DATE_TIME, fenetre.debut())
            .queryParam("endDateTime", fenetre.fin());

    return pageFetcher.fetchToutesPages(uriBase, null, "dept=" + dept).concerts();
  }
}
