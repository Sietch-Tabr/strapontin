/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.sources.sites;

import fr.strapontin.core.ConcertBrut;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

/**
 * Programmation de l'EMB (Sannois) : une carte par événement dans la page
 * agenda. Seuls les événements d'un jour, de type « Concert », sont
 * retenus (ateliers, stages et spectacles jeune public écartés).
 * @since 3.0.6
 * @author Kwaite
 */
public class SiteEmbSannois extends SourceSiteSalle {

  /* package-private : utilisée par les tests. */
  static final String ADRESSE_AGENDA = "https://embsannois.fr/agenda/";
  /* package-private : utilisée par les tests. */
  // Nom réel du lieu (EMB n'en est que le sigle) : « EMB Sannois » ne se
  // rapprochait pas de la fiche Eventim et créait une salle en double.
  static final LieuSite LIEU = new LieuSite("Espace Michel Berger", "Sannois", "95", "95110");

  private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.uuuu");
  private static final Pattern MOTIF_HEURE = Pattern.compile("(\\d{1,2})h(\\d{2})?");
  private static final Pattern PREMIERE_PARTIE =
      Pattern.compile("\\s*\\+\\s*1[èe]re\\s+partie.*$", Pattern.CASE_INSENSITIVE);
  private static final Pattern PREFIXE_SOIREE =
      Pattern.compile("^(?:afterwork|after work)\\s*[-–]\\s*", Pattern.CASE_INSENSITIVE);

  /**
   * @param client lecteur de pages du site
   */
  public SiteEmbSannois(final ClientSiteSalle client) {
    super(client);
  }

  @Override
  public String identifiantSite() {
    return "emb";
  }

  @Override
  protected List<ConcertBrut> collecter(final LecteurPages lecteur) {
    return extraire(lecteur.lire(ADRESSE_AGENDA));
  }

  /* package-private : testé sur une page réelle enregistrée. */
  static List<ConcertBrut> extraire(final String html) {
    final Document page = Jsoup.parse(html);
    final List<ConcertBrut> concerts = new ArrayList<>();
    for (final Element carte : page.select("div.artprog")) {
      final ConcertBrut concert = depuisCarte(carte);
      if (concert != null) {
        concerts.add(concert);
      }
    }
    return concerts;
  }

  private static ConcertBrut depuisCarte(final Element carte) {
    final Element type = carte.selectFirst("#typevt");
    final Element date = carte.selectFirst("#ladate");
    final Element titre = carte.selectFirst("#titreagenda");
    final Element lien = carte.selectFirst("a.lelienartprog");
    if (type == null
        || date == null
        || titre == null
        || lien == null
        || !type.text().strip().toLowerCase(java.util.Locale.ROOT).startsWith("concert")) {
      return null;
    }
    final String artiste = nomArtiste(titre.text());
    if (artiste.isEmpty()) {
      return null;
    }
    try {
      return concert(
          LIEU,
          "emb",
          artiste,
          LocalDate.parse(date.text().strip(), DATE),
          heure(carte.selectFirst("#horaire")),
          lien.attr("href"));
    } catch (DateTimeParseException _) {
      return null;
    }
  }

  private static String nomArtiste(final String titre) {
    final String sansPartie = PREMIERE_PARTIE.matcher(titre.strip()).replaceFirst("");
    return PREFIXE_SOIREE.matcher(sansPartie).replaceFirst("").strip();
  }

  private static LocalTime heure(final Element horaire) {
    if (horaire == null) {
      return null;
    }
    final Matcher trouve = MOTIF_HEURE.matcher(horaire.text());
    if (!trouve.find()) {
      return null;
    }
    return LocalTime.of(
        Integer.parseInt(trouve.group(1)),
        trouve.group(2) == null ? 0 : Integer.parseInt(trouve.group(2)));
  }
}
