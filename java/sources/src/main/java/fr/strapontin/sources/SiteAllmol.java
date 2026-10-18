/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.sources;

import fr.strapontin.core.ConcertBrut;
import fr.strapontin.core.Normalisation;
import fr.strapontin.core.PositionLieu;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jsoup.parser.Parser;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Billetterie Allmol Tickets (Antilles, Guyane) : concerts lus dans le JSON
 * public que charge le site. Le titre d'un produit porte aussi département,
 * date et lieu (« 971 - 17/10 - REGGAE BEATS @ Lacaz'art »), retirés.
 * Horodatages en heure locale du département.
 * @since 3.0.6
 * @author Kwaite
 */
public class SiteAllmol extends SourceSiteSalle {

  /* package-private : adresse du site, utilisée par les tests. */
  static final String BASE = "https://tickets.allmol.com";
  // Clé de la catégorie « concerts » du site ; une clé changée donne une
  // liste vide, signalée par la chute de volume.
  /* package-private : utilisée par les tests. */
  static final String CLE_CATEGORIE_CONCERTS = "J6U6QNNI2YSKZQ";

  private static final int TAILLE_PAGE = 100;
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final Pattern PREFIXE_DEPT_DATE =
      Pattern.compile("^\\d{3}\\s*-\\s*\\d{1,2}/\\d{1,2}\\s*-\\s*");
  private static final Pattern LIEU_DANS_TITRE = Pattern.compile("\\s+@\\s+.*$");
  private static final Pattern EN_CONCERT =
      Pattern.compile("\\s+en\\s+(?:concert|live)\\b.*$", Pattern.CASE_INSENSITIVE);
  private static final Pattern VILLE_APRES_TIRET = Pattern.compile("^(.*?)\\s+[-–]\\s+([^-–]+)$");
  private static final Pattern VILLE_ENTRE_PARENTHESES =
      Pattern.compile("^(.*?)\\s*\\(([^)]+)\\)$");
  private static final Pattern DEPT_OUTRE_MER = Pattern.compile("97\\d");
  private static final Map<String, ZoneId> FUSEAUX =
      Map.of(
          "971", ZoneId.of("America/Guadeloupe"),
          "972", ZoneId.of("America/Martinique"),
          "973", ZoneId.of("America/Cayenne"),
          "974", ZoneId.of("Indian/Reunion"),
          "976", ZoneId.of("Indian/Mayotte"));

  /** Une page de résultats : nombre total de produits et concerts lus. */
  /* package-private : testé directement. */
  record PageProduits(int total, int lus, List<ConcertBrut> concerts) {}

  /**
   * @param client lecteur de pages du site
   */
  public SiteAllmol(final ClientSiteSalle client) {
    super(client);
  }

  @Override
  public String identifiantSite() {
    return "allmol";
  }

  @Override
  protected List<ConcertBrut> collecter(final LecteurPages lecteur) {
    final List<ConcertBrut> concerts = new ArrayList<>();
    int debut = 0;
    while (true) {
      final PageProduits page = extraire(lecteur.lire(adresse(debut)));
      concerts.addAll(page.concerts());
      debut += TAILLE_PAGE;
      if (page.lus() == 0 || debut >= page.total()) {
        return concerts;
      }
    }
  }

  /* package-private : testé sur une réponse réelle enregistrée. */
  static String adresse(final int debut) {
    return BASE
        + "/dataSrc/item/products?for=list&length="
        + TAILLE_PAGE
        + "&start="
        + debut
        + "&sort%5Bvalue%5D=d%3Astartevent&customFilter%5Bcategorykey%5D%5B%5D="
        + CLE_CATEGORIE_CONCERTS;
  }

  /* package-private : testé sur une réponse réelle enregistrée. */
  static PageProduits extraire(final String json) {
    final JsonNode racine = JSON.readTree(json);
    final JsonNode donnees = racine.path("data");
    final List<ConcertBrut> concerts = new ArrayList<>();
    int lus = 0;
    for (final JsonNode produit : donnees) {
      lus++;
      final ConcertBrut concert = depuisProduit(produit);
      if (concert != null) {
        concerts.add(concert);
      }
    }
    return new PageProduits(racine.path("count").asInt(lus), lus, concerts);
  }

  private static ConcertBrut depuisProduit(final JsonNode produit) {
    if (annuleOuReporte(produit)) {
      return null;
    }
    final String dept = texte(produit, "d:area_zip");
    final ZoneId fuseau = dept == null ? null : FUSEAUX.get(dept);
    final String debut = texte(produit, "d:startevent");
    final String titre = nettoyerTitre(texte(produit, "d:name"));
    final String lieu = texte(produit, "d:lieu");
    final String chemin = texte(produit, "d:path");
    if (fuseau == null || debut == null || titre.isEmpty() || lieu == null || chemin == null) {
      return null;
    }
    try {
      final ZonedDateTime horaire = Instant.ofEpochSecond(Long.parseLong(debut)).atZone(fuseau);
      final LieuSite salle = lieuSite(lieu, dept);
      return concert(
          salle,
          "allmol:" + Normalisation.normaliserTexte(salle.nom()),
          titre,
          horaire.toLocalDate(),
          horaire.toLocalTime(),
          BASE + "/fr/p/" + chemin,
          PositionLieu.depuis(texte(produit, "d:latitude"), texte(produit, "d:longitude"), null));
    } catch (NumberFormatException | DateTimeException _) {
      return null;
    }
  }

  private static boolean annuleOuReporte(final JsonNode produit) {
    return "1".equals(texte(produit, "d:event_annuler")) || "1".equals(texte(produit, "d:report"));
  }

  private static String texte(final JsonNode noeud, final String champ) {
    final JsonNode valeur = noeud.path(champ);
    if (valeur.isMissingNode() || valeur.isNull() || valeur.asString().isBlank()) {
      return null;
    }
    return valeur.asString();
  }

  // Le site double parfois l'échappement des entités (&amp;#039;).
  private static String decoder(final String texte) {
    if (texte == null) {
      return "";
    }
    return Parser.unescapeEntities(Parser.unescapeEntities(texte, false), false).strip();
  }

  /* package-private : testé directement. */
  static String nettoyerTitre(final String brut) {
    String titre = decoder(brut);
    titre = PREFIXE_DEPT_DATE.matcher(titre).replaceFirst("");
    titre = LIEU_DANS_TITRE.matcher(titre).replaceFirst("");
    titre = EN_CONCERT.matcher(titre).replaceFirst("");
    return titre.strip();
  }

  // « ZION PARK - Le Gosier », « VÉLODROME GOURDELIANE (BAIE-MAHAULT) » :
  // la commune suit le nom.
  private static LieuSite lieuSite(final String lieuBrut, final String dept) {
    final String lieu = decoder(lieuBrut);
    final Matcher tiret = VILLE_APRES_TIRET.matcher(lieu);
    if (tiret.matches()) {
      return new LieuSite(tiret.group(1).strip(), tiret.group(2).strip(), departement(dept), null);
    }
    final Matcher parentheses = VILLE_ENTRE_PARENTHESES.matcher(lieu);
    if (parentheses.matches()) {
      return new LieuSite(
          parentheses.group(1).strip(), parentheses.group(2).strip(), departement(dept), null);
    }
    return new LieuSite(lieu, null, departement(dept), null);
  }

  private static String departement(final String dept) {
    return dept != null && DEPT_OUTRE_MER.matcher(dept).matches() ? dept : null;
  }
}
