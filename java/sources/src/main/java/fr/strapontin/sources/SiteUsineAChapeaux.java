/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.sources;

import fr.strapontin.core.ConcertBrut;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

/**
 * Programmation de L'Usine à Chapeaux (Rambouillet) : la page
 * « concerts » liste, dans l'ordre du document, une date, un titre puis
 * des étiquettes. Le site ne donne pas l'heure ; les ateliers, tables
 * rondes et formations (étiquettes dédiées) sont écartés.
 * @since 3.0.6
 * @author Kwaite
 */
public class SiteUsineAChapeaux extends SourceSiteSalle {

  /* package-private : utilisée par les tests. */
  static final String ADRESSE_CONCERTS = "https://www.usineachapeaux.fr/concerts/";
  /* package-private : utilisée par les tests. */
  static final LieuSite LIEU = new LieuSite("L'Usine à Chapeaux", "Rambouillet", "78", "78120");

  private static final Pattern DATE_FRANCAISE =
      Pattern.compile("^(\\d{1,2})\\s+(\\p{L}+)\\s+(\\d{4})$");
  private static final Set<String> ETIQUETTES_NON_CONCERT =
      Set.of("atelier", "table ronde", "conférence", "formation", "stage", "rencontre");
  private static final Map<String, Integer> MOIS =
      Map.ofEntries(
          Map.entry("janvier", 1),
          Map.entry("février", 2),
          Map.entry("mars", 3),
          Map.entry("avril", 4),
          Map.entry("mai", 5),
          Map.entry("juin", 6),
          Map.entry("juillet", 7),
          Map.entry("août", 8),
          Map.entry("septembre", 9),
          Map.entry("octobre", 10),
          Map.entry("novembre", 11),
          Map.entry("décembre", 12));

  /** Événement en cours de lecture : date vue, puis titre et étiquettes. */
  private static final class Brouillon {
    private final LocalDate date;
    private String titre;
    private String adresse;
    private final List<String> etiquettes = new ArrayList<>();

    private Brouillon(final LocalDate date) {
      this.date = date;
    }
  }

  /**
   * @param client lecteur de pages du site
   */
  public SiteUsineAChapeaux(final ClientSiteSalle client) {
    super(client);
  }

  @Override
  public String identifiantSite() {
    return "usine";
  }

  @Override
  protected List<ConcertBrut> collecter(final LecteurPages lecteur) {
    return extraire(lecteur.lire(ADRESSE_CONCERTS));
  }

  /* package-private : testé sur une page réelle enregistrée. */
  static List<ConcertBrut> extraire(final String html) {
    final Document page = Jsoup.parse(html);
    final List<Brouillon> evenements = new ArrayList<>();
    Brouillon courant = null;
    for (final Element element : page.getAllElements()) {
      courant = lireElement(element, courant, evenements);
    }
    return evenements.stream()
        .filter(e -> e.titre != null && !e.titre.isEmpty() && estUnConcert(e))
        .map(e -> concert(LIEU, "usine", e.titre, e.date, null, e.adresse))
        .toList();
  }

  // Les éléments de la page se suivent : une date ouvre un événement, dont
  // le titre et les étiquettes viennent ensuite. Renvoie l'événement en
  // cours après cet élément.
  private static Brouillon lireElement(
      final Element element, final Brouillon courant, final List<Brouillon> evenements) {
    if (element.is(".elementor-widget-dce-rawphp span")) {
      final LocalDate date = dateFrancaise(element.text());
      if (date == null) {
        return courant;
      }
      final Brouillon ouvert = new Brouillon(date);
      evenements.add(ouvert);
      return ouvert;
    }
    if (courant == null) {
      return null;
    }
    if (element.is("h4.mec-event-title a")) {
      if (courant.titre == null) {
        courant.titre = element.text().strip();
        courant.adresse = element.attr("href");
      }
    } else if (element.is(".elementor-post-info__terms-list-item")) {
      courant.etiquettes.add(element.text().strip().toLowerCase(Locale.ROOT));
    }
    return courant;
  }

  private static boolean estUnConcert(final Brouillon evenement) {
    return evenement.etiquettes.stream().noneMatch(ETIQUETTES_NON_CONCERT::contains);
  }

  private static LocalDate dateFrancaise(final String texte) {
    final Matcher date = DATE_FRANCAISE.matcher(texte.strip().toLowerCase(Locale.ROOT));
    if (!date.matches() || !MOIS.containsKey(date.group(2))) {
      return null;
    }
    try {
      return LocalDate.of(
          Integer.parseInt(date.group(3)),
          MOIS.get(date.group(2)),
          Integer.parseInt(date.group(1)));
    } catch (DateTimeException _) {
      return null;
    }
  }
}
