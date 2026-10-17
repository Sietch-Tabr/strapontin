/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.sources;

import fr.strapontin.core.ConcertBrut;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

/**
 * Programmation du Triton (Les Lilas) : une fiche par concert dans la page agenda, la date étant
 * celle de l'adresse de la fiche.
 * @since 3.0.6
 * @author Kwaite
 */
public class SiteLeTriton extends SourceSiteSalle {

  static final String ADRESSE_AGENDA = "https://letriton.com/agenda";
  static final LieuSite LIEU = new LieuSite("Le Triton", "Les Lilas", "93", null);

  private static final Pattern DATE_DANS_ADRESSE = Pattern.compile("(\\d{4}-\\d{2}-\\d{2})$");
  private static final Pattern HEURE = Pattern.compile("(\\d{1,2})h(\\d{2})?");

  /**
   * @param client lecteur de pages du site
   */
  public SiteLeTriton(final ClientSiteSalle client) {
    super(client);
  }

  @Override
  public String identifiantSite() {
    return "triton";
  }

  @Override
  protected List<ConcertBrut> collecter(final LecteurPages lecteur) {
    return extraire(lecteur.lire(ADRESSE_AGENDA));
  }

  /* package-private : testé sur une page réelle enregistrée. */
  static List<ConcertBrut> extraire(final String html) {
    final Document page = Jsoup.parse(html);
    final List<ConcertBrut> concerts = new ArrayList<>();
    for (final Element fiche : page.select("article.crow")) {
      final Element lien = fiche.selectFirst("a.crow__main");
      final Element titre = fiche.selectFirst("h3.crow__title");
      if (lien == null || titre == null || titre.ownText().isBlank()) {
        continue;
      }
      final String adresse = lien.attr("href");
      final Matcher date = DATE_DANS_ADRESSE.matcher(adresse);
      if (!date.find()) {
        continue;
      }
      try {
        concerts.add(
            concert(
                LIEU,
                "triton",
                titre.ownText().strip(),
                LocalDate.parse(date.group(1)),
                heure(fiche.selectFirst(".crow__when")),
                adresse));
      } catch (DateTimeParseException _) {
        // Adresse dont la fin ressemble à une date invalide : fiche ignorée.
      }
    }
    return concerts;
  }

  private static LocalTime heure(final Element quand) {
    if (quand == null) {
      return null;
    }
    final Matcher m = HEURE.matcher(quand.text());
    if (!m.find()) {
      return null;
    }
    return LocalTime.of(
        Integer.parseInt(m.group(1)), m.group(2) == null ? 0 : Integer.parseInt(m.group(2)));
  }
}
