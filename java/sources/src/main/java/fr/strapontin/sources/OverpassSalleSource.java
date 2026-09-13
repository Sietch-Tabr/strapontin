/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.sources;

import fr.strapontin.core.BilanFetch;
import fr.strapontin.core.CompteurBilanFetch;
import fr.strapontin.core.Normalisation;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

/**
 * Client Overpass (OSM) pour l'enrichissement initial des salles par
 * nom — non trouvées, elles restent éligibles à la recherche Wikidata
 * directe ({@link WikidataSalleSource}). Détail (requête par
 * département, incident overpass.osm.ch) : CLAUDE.md.
 * @since 2.6.0
 * @author Kwaite
 */
public class OverpassSalleSource implements SourceEnrichissementSalle, SourceAvecBilanFetch {

  private static final Logger LOG = LoggerFactory.getLogger(OverpassSalleSource.class);

  private final CompteurBilanFetch compteurBilan = new CompteurBilanFetch();
  // Champ (et non variable locale d'enrichirLot) pour que le disjoncteur
  // protège tout le cycle nocturne, pas seulement le lot de persistance courant
  // (EnrichissementSallesService rappelle enrichirLot une fois par lot de
  // TAILLE_LOT_PERSISTANCE) — remis à zéro par preleverBilan, déjà appelé une
  // seule fois par cycle, pour se réarmer automatiquement au cycle suivant.
  private final AtomicInteger echecsConsecutifsCycle = new AtomicInteger();

  private static final String BASE_URL = "https://overpass-api.de/api/interpreter";
  // Identification obligatoire côté serveur (User-Agent générique/absent →
  // 406).
  private static final String USER_AGENT =
      "Strapontin/2.6.0 (https://codeberg.org/Kwaite/strapontin; kwaite@hotmail.fr) SpringBoot/4.1";
  private static final int TAILLE_LOT = 25;
  // Constaté en test direct (hors cycle nocturne) : le rate-limit (429) et la
  // saturation (504, "server too busy") d'overpass-api.de se déclenchent en
  // quelques secondes déjà à notre propre cadence d'appels — l'instance
  // publique est partagée avec tous ses autres utilisateurs, notre délai
  // minimal entre lots n'a donc qu'un effet partiel sur la contention globale,
  // mais rester en dessous de notre part raisonnable évite d'aggraver une
  // saturation déjà là.
  private static final int DELAI_MIN_ENTRE_APPELS_MS = 3000;
  // Un seul retry suffit à absorber un aléa réseau isolé sans faire pendre le
  // cycle nocturne sur une instance en difficulté passagère. Deux natures
  // d'échec distinctes : 429 (rate-limit, potentiellement partagé avec d'autres
  // utilisateurs de l'instance) où réessayer trop vite ne fait qu'ajouter de la
  // charge à un service déjà saturé — backoff long ; 504/erreur réseau (aléa
  // isolé, souvent résolu en quelques secondes) — backoff court, inchangé
  // depuis 2.7.8.
  private static final int NB_ESSAIS_MAX = 2;
  private static final long DELAI_APRES_ERREUR_MS = 5000;
  private static final long DELAI_APRES_429_MS = 20_000;
  // Constaté en production : une fenêtre d'indisponibilité de l'instance
  // publique (connexion refusée pendant 1-2 minutes, probablement un
  // redémarrage du dispatcher) fait échouer chaque lot départemental consécutif
  // sans jamais s'arrêter, brûlant tout le reste du cycle en retries voués à
  // l'échec. Après ce seuil d'échecs consécutifs, mieux vaut abandonner le
  // cycle en cours (les candidats non traités restent éligibles au prochain,
  // rotation jour_batch) que de continuer à marteler un service manifestement
  // indisponible.
  private static final int SEUIL_ECHECS_CONSECUTIFS = 3;
  // Le timeout serveur ([timeout:...] dans la requête OverpassQL) doit rester
  // strictement inférieur au timeout de lecture HTTP client pour laisser le
  // temps à Overpass de renvoyer sa propre réponse d'erreur "Query timed out"
  // plutôt que de couper la connexion en premier.
  private static final int TIMEOUT_SERVEUR_SECONDES = 25;
  private static final Duration TIMEOUT_LECTURE_CLIENT = Duration.ofSeconds(35);
  // Rayon de recherche autour du centre géographique du département, en mètres
  // — légèrement plus large que le rayon TM (CentreDept#rayon, en km) pour
  // couvrir les salles proches d'une frontière départementale sans avoir à
  // interroger les deux départements voisins.
  private static final int MARGE_RAYON_METRES = 5_000;

  private static final Pattern METACARACTERES_REGEX = Pattern.compile("([.^$|()\\[\\]{}*+?\\\\])");
  private static final Pattern APOSTROPHE_DROITE = Pattern.compile("'");

  private final RestClient restClient;
  private final CadenceurAppels cadenceur = new CadenceurAppelsMemoire(DELAI_MIN_ENTRE_APPELS_MS);

  /**
   * Retourne le bilan agrégé des appels HTTP Overpass depuis le dernier
   * prélèvement et remet les compteurs à zéro — réarme aussi le disjoncteur
   * pour le prochain cycle.
   *
   * @return le bilan depuis le dernier prélèvement
   */
  @Override
  public BilanFetch preleverBilan() {
    echecsConsecutifsCycle.set(0);
    return compteurBilan.preleverBilan();
  }

  // Un seul déclenchement du disjoncteur (SEUIL_ECHECS_CONSECUTIFS échecs,
  // jusqu'à NB_ESSAIS_MAX tentatives chacun) suffit à atteindre
  // BilanFetch.SEUIL_APPELS_MIN_ALERTE_DEFAUT (3) : sans seuil dédié plus
  // élevé, toute coupure brève de l'instance publique escaladerait
  // systématiquement en alerte email "panne totale", alors que le WARN du
  // disjoncteur signale déjà l'incident. Une dégradation qui persiste sur
  // plusieurs cycles reste couverte par alerterSiCycleImproductif
  // (EnrichissementSallesService), mécanisme distinct.
  @Override
  public int seuilAppelsMinAlerte() {
    return SEUIL_ECHECS_CONSECUTIFS * NB_ESSAIS_MAX + 1;
  }

  /**
   * Crée une instance avec un RestClient par défaut (timeout connexion 5s,
   * lecture 35s).
   */
  public OverpassSalleSource() {
    this(construireRestClientParDefaut());
  }

  private static RestClient construireRestClientParDefaut() {
    final SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(Duration.ofSeconds(5));
    factory.setReadTimeout(TIMEOUT_LECTURE_CLIENT);
    return RestClient.builder().requestFactory(factory).build();
  }

  /**
   * Crée une instance avec un RestClient personnalisé (injection de test).
   *
   * @param restClient client HTTP à utiliser
   */
  public OverpassSalleSource(final RestClient restClient) {
    this.restClient = restClient;
  }

  @Override
  public String nom() {
    return "overpass";
  }

  @Override
  public Map<UUID, EnrichissementSalle> enrichirLot(final List<SalleAEnrichir> salles) {
    final List<SalleAEnrichir> aTraiter = filtrerSallesEligibles(salles);
    if (aTraiter.isEmpty()) {
      return Map.of();
    }
    if (echecsConsecutifsCycle.get() >= SEUIL_ECHECS_CONSECUTIFS) {
      LOG.debug("Overpass — disjoncteur déjà ouvert pour ce cycle, lot ignoré sans appel HTTP");
      return Map.of();
    }

    final Map<UUID, EnrichissementSalle> resultats = new HashMap<>();
    final Map<String, List<SalleAEnrichir>> parDept =
        aTraiter.stream().collect(Collectors.groupingBy(SalleAEnrichir::dept));
    for (final Map.Entry<String, List<SalleAEnrichir>> entree : parDept.entrySet()) {
      if (enrichirDept(entree.getKey(), entree.getValue(), resultats)) {
        break;
      }
    }
    return resultats;
  }

  private List<SalleAEnrichir> filtrerSallesEligibles(final List<SalleAEnrichir> salles) {
    final List<SalleAEnrichir> aTraiter =
        salles.stream().filter(s -> s.wikidataId() == null && s.dept() != null).toList();
    final int sansDept =
        (int) salles.stream().filter(s -> s.wikidataId() == null).count() - aTraiter.size();
    if (sansDept > 0) {
      LOG.debug("Overpass — {} salle(s) ignorée(s), département inconnu", sansDept);
    }
    return aTraiter;
  }

  /**
   * Enrichit un département par lots de {@value #TAILLE_LOT} .
   *
   * @return {@code true} si le disjoncteur s'est déclenché pendant ce
   *     département (cycle interrompu, candidats restants retentés au prochain
   *     cycle).
   */
  private boolean enrichirDept(
      final String dept,
      final List<SalleAEnrichir> sallesDept,
      final Map<UUID, EnrichissementSalle> resultats) {
    final CentreDept centre = CentresDepartement.PAR_DEPT.get(dept);
    if (centre == null) {
      return false;
    }
    for (int i = 0; i < sallesDept.size(); i += TAILLE_LOT) {
      final List<SalleAEnrichir> lot =
          sallesDept.subList(i, Math.min(i + TAILLE_LOT, sallesDept.size()));
      cadenceur.attendre();
      final ResultatLot resultat = enrichirUnLot(lot, centre);
      resultats.putAll(resultat.resultats());
      if (resultat.succes()) {
        echecsConsecutifsCycle.set(0);
      } else {
        final int echecs = echecsConsecutifsCycle.incrementAndGet();
        if (echecs >= SEUIL_ECHECS_CONSECUTIFS) {
          LOG.warn(
              "Overpass — {} échecs consécutifs, instance probablement indisponible, cycle"
                  + " interrompu (candidats restants retentés au prochain cycle)",
              echecs);
          return true;
        }
      }
    }
    return false;
  }

  /**
   * Résultat d'un lot Overpass, distinguant un échec définitif (retries
   * épuisés) d'un succès — une réponse réussie sans le moindre élément trouvé
   * n'est pas un échec.
   */
  private record ResultatLot(Map<UUID, EnrichissementSalle> resultats, boolean succes) {}

  private ResultatLot enrichirUnLot(final List<SalleAEnrichir> lot, final CentreDept centre) {
    final String requete = construireRequeteOverpassQL(lot, centre);
    Exception derniereErreur = null;
    for (int essai = 1; essai <= NB_ESSAIS_MAX; essai++) {
      compteurBilan.enregistrerAppel();
      try {
        @SuppressWarnings("unchecked")
        final Map<String, Object> data =
            restClient
                .post()
                .uri(BASE_URL)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body("data=" + URLEncoder.encode(requete, StandardCharsets.UTF_8))
                .retrieve()
                .body(Map.class);
        return new ResultatLot(mapperReponse(data, lot), true);
      } catch (Exception e) {
        derniereErreur = e;
        compteurBilan.enregistrerErreur();
        if (essai < NB_ESSAIS_MAX) {
          attendre(delaiAvantRetry(e));
        }
      }
    }
    LOG.warn("Overpass erreur lot taille={} : {}", lot.size(), derniereErreur.getMessage());
    return new ResultatLot(Map.of(), false);
  }

  /**
   * Détermine le délai avant retry selon la nature de l'échec — accessible aux
   * tests sans déclencher d'attente réelle.
   *
   * @param exception exception levée par l'appel HTTP
   * @return délai en millisecondes avant le prochain essai
   */
  /* package-private : visibilité élargie pour OverpassSalleSourceTest (même
   * package). */
  static long delaiAvantRetry(final Exception exception) {
    return exception instanceof HttpClientErrorException.TooManyRequests
        ? DELAI_APRES_429_MS
        : DELAI_APRES_ERREUR_MS;
  }

  // Thread.sleep est la seule primitive de cadencement synchrone bloquant
  // disponible — pas un contexte de requête J2EE (appel HTTP sortant en job
  // Quartz), la justification de la règle ne s'applique pas ici.
  @SuppressWarnings("PMD.DoNotUseThreads")
  private static void attendre(final long ms) {
    try {
      Thread.sleep(ms);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  // OSM tague fréquemment ses noms avec l'apostrophe typographique courbe (’,
  // U+2019) plutôt que l'apostrophe droite (', U+0027) utilisée dans les noms
  // Strapontin (issus de TM/Eventim/Digitick). Le filtre ["name"~"regex"] est
  // appliqué côté serveur Overpass sur la chaîne brute : sans cette
  // substitution, une regex contenant l'apostrophe droite ne matche jamais un
  // nom OSM en apostrophe courbe (constaté sur « L'Empreinte » à
  // Savigny-le-Temple). La normalisation de Normalisation.normaliserTexte,
  // elle, n'intervient qu'après réception de la réponse (mapperReponse) et ne
  // peut pas rattraper un élément qu'Overpass n'a jamais renvoyé.
  private static String echapperRegex(final String nom) {
    final String echappeMeta = METACARACTERES_REGEX.matcher(nom).replaceAll("\\\\$1");
    // Le guillemet droit est échappé en dernier, après les métacaractères regex
    // : la regex produite ici est elle-même insérée dans un littéral OverpassQL
    // délimité par des guillemets ("name"~"regex") — un guillemet non échappé
    // dans le nom d'une salle romprait ce littéral et permettrait d'injecter
    // des clauses OverpassQL arbitraires dans la requête. Fait avant
    // l'échappement des métacaractères, le backslash qu'il insère serait
    // lui-même ré-échappé (le backslash fait partie des métacaractères),
    // produisant un antislash surnuméraire.
    final String echappeGuillemets = echappeMeta.replace("\"", "\\\"");
    return APOSTROPHE_DROITE.matcher(echappeGuillemets).replaceAll("['’]");
  }

  private static String construireRequeteOverpassQL(
      final List<SalleAEnrichir> lot, final CentreDept centre) {
    final String nomsRegex =
        lot.stream().map(s -> echapperRegex(s.nom())).collect(Collectors.joining("|"));
    final int rayonMetres = centre.rayon() * 1000 + MARGE_RAYON_METRES;
    final String around =
        "around:%d,%s,%s"
            .formatted(
                rayonMetres,
                String.format(Locale.ROOT, "%.4f", centre.lat()),
                String.format(Locale.ROOT, "%.4f", centre.lng()));
    return """
    [out:json][timeout:%d];
    (
      nwr["amenity"~"^(music_venue|arts_centre|theatre)$"]["name"~"%s",i](%s);
    );
    out tags center;
    """
        .formatted(TIMEOUT_SERVEUR_SECONDES, nomsRegex, around);
  }

  // break en dernière position de boucle nécessaire : arrête la recherche au
  // premier tag correspondant plutôt que de poursuivre l'itération et risquer
  // d'écraser resultats avec une correspondance ultérieure moins pertinente.
  @SuppressWarnings({"unchecked", "PMD.AvoidBranchingStatementAsLastInLoop"})
  private static Map<UUID, EnrichissementSalle> mapperReponse(
      final Map<String, Object> data, final List<SalleAEnrichir> lot) {
    final Map<UUID, EnrichissementSalle> resultats = new HashMap<>();
    if (data == null) {
      return resultats;
    }
    final List<Map<String, Object>> elements = (List<Map<String, Object>>) data.get("elements");
    if (elements == null) {
      return resultats;
    }

    for (final SalleAEnrichir salle : lot) {
      for (final Map<String, Object> element : elements) {
        final Map<String, String> tags = (Map<String, String>) element.get("tags");
        if (tags == null) {
          continue;
        }
        final String nomTag = tags.get("name");
        if (nomTag == null || !Normalisation.normaliserTexte(nomTag).equals(salle.nomNorm())) {
          continue;
        }
        resultats.put(salle.id(), construireEnrichissement(tags));
        break;
      }
    }
    return resultats;
  }

  private static EnrichissementSalle construireEnrichissement(final Map<String, String> tags) {
    Integer capacite = null;
    final String capaciteStr = tags.get("capacity");
    if (capaciteStr != null) {
      try {
        capacite = Integer.parseInt(capaciteStr.replaceAll("[^0-9]", ""));
      } catch (NumberFormatException e) {
        LOG.trace("capacité OSM non exploitable : {}", capaciteStr, e);
      }
    }
    return new EnrichissementSalle(
        tags.get("wikidata"),
        tags.get("name:fr"),
        tags.get("addr:city"),
        tags.get("website"),
        capacite);
  }
}
