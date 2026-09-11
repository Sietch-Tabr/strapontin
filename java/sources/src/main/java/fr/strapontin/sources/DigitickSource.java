/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.sources;

import fr.strapontin.core.BilanFetch;
import fr.strapontin.core.CompteurBilanFetch;
import fr.strapontin.core.ConcertBrut;
import fr.strapontin.core.Normalisation;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Client de scraping pour la billetterie Digitick (web.digitick.com).
 *
 * <p>Scrape la page de billetterie d'une salle identifiée par son slug Digitick. L'identifiant
 * préfixé attendu est {@code "digitick:{slug}"}, ex. {@code "digitick:lempreinte"}.
 *
 * <p>Filtre appliqué : genre {@code "Concert"} et code postal présent (exclut les adhésions et
 * packs multi-dates sans salle fixe).
 *
 * @since 2.4.0
 * @author Kwaite
 */
public class DigitickSource implements SourceConcerts, SourceAvecBilanFetch {

  private static final Logger log = LoggerFactory.getLogger(DigitickSource.class);

  static final String PREFIXE = "digitick:";
  private static final String URL_BILLETTERIE =
      "https://web.digitick.com/ext/billetterie5/index.php?site={slug}&action=ajax&op=1";
  private static final Charset CHARSET_DIGITICK = StandardCharsets.ISO_8859_1;
  private static final String GENRE_CONCERT = "Concert";
  private static final String SEL_EVENEMENTS = "dl[class~=evenement\\d+]";
  private static final String SEL_NOM = "a.evenementNom";
  private static final String SEL_DATETIME = "time[datetime]";
  private static final String SEL_GENRE = "div[itemprop=eventType]";
  private static final String SEL_SALLE_NOM = "span.evenementSalleNom";
  private static final String SEL_VILLE = "span.evenementSalleVille";
  private static final String SEL_CODE_POSTAL = "span.evenementSalleCodePostal";
  private static final String SEL_URL = "a.evenementReserver";

  private final RestClient restClient;
  // Bilan de panne agrégé, remonté dans logs_admin par le worker.
  private final CompteurBilanFetch compteurBilan = new CompteurBilanFetch();

  /**
   * Crée une instance avec User-Agent navigateur standard et HTTP/1.1 forcé.
   */
  public DigitickSource() {
    this(creerRestClient());
  }

  private static RestClient creerRestClient() {
    final SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(Duration.ofSeconds(10));
    factory.setReadTimeout(Duration.ofSeconds(15));
    return RestClient.builder()
        .requestFactory(factory)
        .defaultHeader(
            "User-Agent",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
                + " (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36")
        .defaultHeader("Accept", "text/html,application/xhtml+xml,*/*")
        .defaultHeader("Accept-Language", "fr-FR,fr;q=0.9")
        .build();
  }

  /**
   * Crée une instance avec un RestClient personnalisé (injection de test).
   *
   * @param restClient client HTTP à utiliser
   */
  public DigitickSource(final RestClient restClient) {
    this.restClient = Objects.requireNonNull(restClient, "restClient");
  }

  @Override
  public String nom() {
    return "digitick";
  }

  @Override
  public boolean accepteIdentifiantSalle(final String identifiant) {
    return identifiant != null && identifiant.startsWith(PREFIXE);
  }

  /**
   * Récupère les concerts d'une salle Digitick par son identifiant préfixé.
   *
   * @param identifiant identifiant de la forme {@code "digitick:{slug}"}
   * @return liste des concerts trouvés, vide si la salle est inaccessible ou sans concert
   */
  @Override
  public List<ConcertBrut> fetchSalle(final String identifiant) {
    final String slug = identifiant.substring(PREFIXE.length());
    if (slug.isBlank()) {
      log.warn("Digitick fetchSalle — slug vide pour identifiant={}", identifiant);
      return List.of();
    }
    compteurBilan.enregistrerAppel();
    try {
      final byte[] octets = restClient.get().uri(URL_BILLETTERIE, slug).retrieve().body(byte[].class);
      if (octets == null || octets.length == 0) {
        log.warn("Digitick fetchSalle — réponse vide slug={}", slug);
        return List.of();
      }
      final String html = new String(octets, CHARSET_DIGITICK);
      final List<ConcertBrut> concerts = parserConcerts(html, slug);
      log.info("Digitick fetchSalle slug={} total={}", slug, concerts.size());
      return concerts;
    } catch (Exception e) {
      compteurBilan.enregistrerErreur();
      log.warn("Digitick fetchSalle erreur slug={} : {}", slug, e.getMessage());
      return List.of();
    }
  }

  /**
   * Retourne le bilan agrégé des appels HTTP Digitick depuis le dernier prélèvement et remet les
   * compteurs à zéro.
   *
   * @return le bilan depuis le dernier prélèvement
   */
  @Override
  public BilanFetch preleverBilan() {
    return compteurBilan.preleverBilan();
  }

  /**
   * Parse le HTML Digitick et extrait les concerts.
   *
   * @param html HTML brut (ISO-8859-1 déjà décodé en String)
   * @param slug slug de la salle, utilisé pour construire le {@code salleSourceId}
   * @return liste de concerts filtrés
   */
  static List<ConcertBrut> parserConcerts(final String html, final String slug) {
    final Document doc = Jsoup.parse(html);
    final Elements evenements = doc.select(SEL_EVENEMENTS);
    final List<ConcertBrut> concerts = new ArrayList<>();

    for (final Element ev : evenements) {
      final ConcertBrut concert = parserEvenement(ev, slug);
      if (concert != null) {
        concerts.add(concert);
      }
    }
    return concerts;
  }

  private static ConcertBrut parserEvenement(final Element ev, final String slug) {
    // Filtre genre : uniquement les concerts
    final Element genreEl = ev.selectFirst(SEL_GENRE);
    if (genreEl == null || !GENRE_CONCERT.equalsIgnoreCase(genreEl.ownText().trim())) {
      return null;
    }

    // Filtre CP : exclut packs multi-dates sans salle fixe
    final Element cpEl = ev.selectFirst(SEL_CODE_POSTAL);
    if (cpEl == null || cpEl.text().isBlank()) {
      return null;
    }

    // Date et heure depuis l'attribut datetime ISO 8601
    final Element timeEl = ev.selectFirst(SEL_DATETIME);
    if (timeEl == null) {
      return null;
    }
    final String[] datHeure = parserDatetime(timeEl.attr("datetime"));
    if (datHeure == null) {
      return null;
    }

    // Nom de l'artiste : premier lien .evenementNom, entités HTML déjà décodées par Jsoup
    final Element nomEl = ev.selectFirst(SEL_NOM);
    if (nomEl == null || nomEl.text().isBlank()) {
      return null;
    }
    final String artiste = normaliserCasse(nomEl.text().trim());

    // Salle
    final Element salleNomEl = ev.selectFirst(SEL_SALLE_NOM);
    final String salle = salleNomEl != null ? salleNomEl.text().trim() : "Salle inconnue";

    // Ville : le premier span.evenementSalleVille commence par "- Ville", le second est la ville
    final Elements villeEls = ev.select(SEL_VILLE);
    final String ville = villeEls.size() >= 2 ? villeEls.get(1).text().trim() : null;

    // Département depuis code postal
    final String cp = cpEl.text().trim();
    final String dept = Normalisation.extraireDeptFrancais(cp);

    // URL d'achat
    final Element urlEl = ev.selectFirst(SEL_URL);
    final String url = urlEl != null ? urlEl.attr("href") : null;

    return new ConcertBrut(
        artiste,
        datHeure[0],
        datHeure[1],
        salle,
        ville,
        dept,
        url,
        "digitick",
        null,
        GENRE_CONCERT,
        null,
        null,
        null,
        PREFIXE + slug,
        null);
  }

  /**
   * Convertit un nom Digitick en ALL CAPS vers une casse lisible.
   *
   * <p>Capitalise la première lettre de chaque mot ; un mot commence après tout caractère
   * non-alphabétique (espace, tiret, {@code +}, {@code :}, etc.).
   *
   * @param nom nom brut en ALL CAPS (ex. {@code "RONNIE ROMERO + GUS G"})
   * @return nom en casse normale (ex. {@code "Ronnie Romero + Gus G"})
   */
  static String normaliserCasse(final String nom) {
    if (nom == null || nom.isBlank()) {
      return nom;
    }
    final String lower = nom.toLowerCase(Locale.FRENCH);
    final StringBuilder sb = new StringBuilder(lower.length());
    boolean capitaliser = true;
    for (final char c : lower.toCharArray()) {
      if (Character.isLetter(c)) {
        sb.append(capitaliser ? Character.toUpperCase(c) : c);
        capitaliser = false;
      } else {
        sb.append(c);
        capitaliser = true;
      }
    }
    return sb.toString();
  }

  /**
   * Parse un datetime ISO 8601 Digitick et retourne {@code [date, heure]}.
   *
   * <p>L'heure {@code "00:00"} est considérée absente (Digitick utilise minuit par défaut).
   *
   * @param datetime valeur de l'attribut {@code datetime}, ex. {@code "2026-10-07T20:00:00"}
   * @return tableau {@code [date, heure]} ou {@code null} si le format est invalide
   */
  static String[] parserDatetime(final String datetime) {
    if (datetime == null || datetime.length() < 10) {
      return null;
    }
    final String date = datetime.substring(0, 10);
    if (datetime.length() < 16) {
      return new String[]{date, null};
    }
    final String heure = datetime.substring(11, 16);
    return new String[] {date, "00:00".equals(heure) ? null : heure};
  }
}
