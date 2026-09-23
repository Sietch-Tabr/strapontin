/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.sources;

import fr.strapontin.core.BilanFetch;
import fr.strapontin.core.CompteurBilanFetch;
import fr.strapontin.core.ConcertBrut;
import fr.strapontin.core.Normalisation;
import java.net.http.HttpClient;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Client de scraping pour la billetterie Digitick (identifiant préfixé
 * {@code "digitick:{slug}"}) — filtre genre {@code "Concert"} et code
 * postal présent (exclut adhésions/packs multi-dates sans salle fixe).
 * @since 2.4.0
 * @author Kwaite
 */
public class DigitickSource implements SourceConcerts, SourceAvecBilanFetch {

  private static final Logger LOG = LoggerFactory.getLogger(DigitickSource.class);

  private static final String PREFIXE = "digitick:";
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
  // Longueur d'un datetime ISO complet avec heure ("yyyy-MM-ddTHH:mm") — en
  // dessous, seule la date est présente.
  private static final int LONGUEUR_DATETIME_AVEC_HEURE = 16;

  private final RestClient restClient;
  // Bilan de panne agrégé, remonté dans logs_admin par le worker.
  private final CompteurBilanFetch compteurBilan = new CompteurBilanFetch();

  /**
   * Crée une instance avec User-Agent navigateur standard et HTTP/1.1 forcé.
   */
  public DigitickSource() {
    this(creerRestClient());
  }

  // HttpClient jamais fermé : partagé, pool de connexions réutilisé, pas un
  // usage ponctuel.
  @SuppressWarnings("PMD.CloseResource")
  private static RestClient creerRestClient() {
    final HttpClient httpClient =
        HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    final JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
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
   * @return liste des concerts trouvés, vide si la salle est inaccessible ou
   *     sans concert
   */
  @Override
  public List<ConcertBrut> fetchSalle(final String identifiant) {
    final String slug = identifiant.substring(PREFIXE.length());
    if (slug.isBlank()) {
      LOG.warn("Digitick fetchSalle — slug vide pour identifiant={}", identifiant);
      return List.of();
    }
    compteurBilan.enregistrerAppel();
    try {
      final byte[] octets =
          restClient.get().uri(URL_BILLETTERIE, slug).retrieve().body(byte[].class);
      if (octets == null || octets.length == 0) {
        LOG.warn("Digitick fetchSalle — réponse vide slug={}", slug);
        return List.of();
      }
      final String html = new String(octets, CHARSET_DIGITICK);
      final List<ConcertBrut> concerts = parserConcerts(html, slug);
      LOG.info("Digitick fetchSalle slug={} total={}", slug, concerts.size());
      return concerts;
    } catch (Exception e) {
      compteurBilan.enregistrerErreur();
      LOG.warn("Digitick fetchSalle erreur slug={} : {}", slug, e.getMessage());
      return List.of();
    }
  }

  /**
   * Retourne le bilan agrégé des appels HTTP Digitick depuis le dernier
   * prélèvement et remet les compteurs à zéro.
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
   * @param slug slug de la salle, utilisé pour construire le
   *     {@code salleSourceId}
   * @return liste de concerts filtrés
   */
  /* package-private : visibilité élargie pour DigitickSourceTest (même
   * package). */
  static List<ConcertBrut> parserConcerts(final String html, final String slug) {
    final Document doc = Jsoup.parse(html);
    final List<Element> evenements = doc.select(SEL_EVENEMENTS);
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
    if (!estConcertValide(ev)) {
      return null;
    }

    // Date et heure depuis l'attribut datetime ISO 8601
    final Element timeEl = ev.selectFirst(SEL_DATETIME);
    final String[] datHeure = timeEl != null ? parserDatetime(timeEl.attr("datetime")) : null;
    if (datHeure == null) {
      return null;
    }

    // Nom de l'artiste : premier lien .evenementNom, entités HTML déjà décodées
    // par Jsoup
    final Element nomEl = ev.selectFirst(SEL_NOM);
    if (nomEl == null || nomEl.text().isBlank()) {
      return null;
    }
    final String artiste = normaliserCasse(nomEl.text().trim());

    return construireConcertDigitick(ev, slug, datHeure, artiste);
  }

  /**
   * Filtre genre (uniquement les concerts) et code postal (exclut les packs
   * multi-dates sans salle fixe).
   *
   * @param ev élément HTML de l'événement
   * @return {@code true} si l'événement passe les deux filtres
   */
  private static boolean estConcertValide(final Element ev) {
    final Element genreEl = ev.selectFirst(SEL_GENRE);
    if (genreEl == null || !GENRE_CONCERT.equalsIgnoreCase(genreEl.ownText().trim())) {
      return false;
    }
    final Element cpEl = ev.selectFirst(SEL_CODE_POSTAL);
    return cpEl != null && !cpEl.text().isBlank();
  }

  private static ConcertBrut construireConcertDigitick(
      final Element ev, final String slug, final String[] datHeure, final String artiste) {
    // Salle
    final Element salleNomEl = ev.selectFirst(SEL_SALLE_NOM);
    final String salle = salleNomEl != null ? salleNomEl.text().trim() : "Salle inconnue";

    // Ville : le premier span.evenementSalleVille commence par "- Ville", le
    // second est la ville
    final List<Element> villeEls = ev.select(SEL_VILLE);
    final String ville = villeEls.size() >= 2 ? villeEls.get(1).text().trim() : null;

    // Département depuis code postal (déjà validé non vide par
    // estConcertValide)
    final String cp = ev.selectFirst(SEL_CODE_POSTAL).text().trim();
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
   * Convertit un nom Digitick ALL CAPS vers une casse lisible — capitalise
   * la première lettre de chaque mot (mot = après tout caractère
   * non-alphabétique : espace, tiret, +, :).
   * @param nom nom brut en ALL CAPS (ex. "RONNIE ROMERO + GUS G")
   * @return nom en casse normale (ex. "Ronnie Romero + Gus G")
   */
  /* package-private : visibilité élargie pour DigitickSourceTest (même
   * package). */
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
   * Parse un datetime ISO 8601 Digitick vers {@code [date, heure]} —
   * l'heure "00:00" est considérée absente (minuit par défaut).
   * @param datetime valeur de l'attribut datetime, ex. "2026-10-07T20:00:00"
   * @return tableau [date, heure] ou null si le format est invalide
   */
  // Le tableau représente une paire date/heure, pas une collection à agréger —
  // l'appelant teste explicitement null pour distinguer "format invalide" d'un
  // tableau vide, sémantiques différentes.
  @SuppressWarnings("PMD.ReturnEmptyCollectionRatherThanNull")
  /* package-private : visibilité élargie pour DigitickSourceTest (même
   * package). */
  static String[] parserDatetime(final String datetime) {
    if (datetime == null || datetime.length() < 10) {
      return null;
    }
    final String date = datetime.substring(0, 10);
    if (datetime.length() < LONGUEUR_DATETIME_AVEC_HEURE) {
      return new String[] {date, null};
    }
    final String heure = datetime.substring(11, 16);
    return new String[] {date, "00:00".equals(heure) ? null : heure};
  }
}
