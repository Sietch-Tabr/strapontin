/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.sources.eventim;

import fr.strapontin.core.CompteurBilanFetch;
import fr.strapontin.core.Normalisation;
import fr.strapontin.core.UtilitairesChaine;
import fr.strapontin.sources.http.CauseEchecHttp;
import fr.strapontin.sources.http.UriSources;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Résolution de l'attractionId Eventim correspondant à un nom d'artiste,
 * via l'API attractions — extrait d'{@link EventimSource}.
 * @since 2.9.1
 * @author Kwaite
 */
final class EventimAttractionResolver {

  private static final String CLE_NOM = "name";
  private static final Logger LOG = LoggerFactory.getLogger(EventimAttractionResolver.class);
  private static final String PARAM_WEB_ID = "webId";
  private static final String PARAM_LANGUE = "language";

  private final RestClient restClient;
  private final CompteurBilanFetch compteurBilan;
  private final String baseUrl;
  private final String webId;
  // correspondances par containment acceptées depuis le dernier prélèvement —
  // un containment n'est pas nécessairement une erreur (ex. "-M-" → "-M-
  // Matthieu Chedid" est correct) mais reste un signal à vérifier manuellement,
  // jusque-là invisible (log SLF4J niveau INFO uniquement, jamais dans
  // logs_admin).
  private final List<String> containmentsAcceptes = new ArrayList<>();
  private final ReentrantLock verrouContainments = new ReentrantLock();

  /**
   * @param restClient client HTTP Eventim partagé
   * @param compteurBilan compteur d'appels/erreurs partagé avec
   *     {@link EventimSource}
   * @param baseUrl URL de base de l'API exploration Eventim
   * @param webId identifiant web Eventim (paramètre {@code webId} de chaque
   *     requête)
   */
  /* package-private : instancié uniquement par EventimSource (même package). */
  EventimAttractionResolver(
      final RestClient restClient,
      final CompteurBilanFetch compteurBilan,
      final String baseUrl,
      final String webId) {
    this.restClient = restClient;
    this.compteurBilan = compteurBilan;
    this.baseUrl = baseUrl;
    this.webId = webId;
  }

  /**
   * Retourne les correspondances par containment acceptées depuis le
   * dernier prélèvement et vide la liste — un containment mal résolu peut
   * réapparaître à chaque fetch, l'attractionId n'étant jamais persisté.
   * @return messages décrivant chaque containment accepté
   */
  /* package-private : appelé par EventimSource (même package). */
  List<String> preleverContainmentsAcceptes() {
    verrouContainments.lock();
    try {
      final List<String> copie = List.copyOf(containmentsAcceptes);
      containmentsAcceptes.clear();
      return copie;
    } finally {
      verrouContainments.unlock();
    }
  }

  /**
   * Recherche l'attractionId Eventim correspondant à un terme de recherche.
   * @param terme nom de l'artiste cherché
   * @param identiteConfirmee true désactive le repli par containment,
   *     n'accepte plus qu'une correspondance exacte
   * @return attractionId trouvé, ou null si aucune correspondance
   */
  /* package-private : appelé par EventimSource (même package). */
  String rechercherAttractionId(final String terme, final boolean identiteConfirmee) {
    return rechercherAttractionId(terme, identiteConfirmee, List.of());
  }

  /**
   * @param identiteConfirmee true : repli par containment limité aux noms
   *     dont le reste est un autre nom connu (libellé, alias Wikidata)
   * @return attractionId trouvé, ou null si aucune correspondance
   */
  @SuppressWarnings("unchecked")
  /* package-private : appelé par EventimSource (même package). */
  String rechercherAttractionId(
      final String terme, final boolean identiteConfirmee, final List<String> autresNomsConnus) {
    final URI uri = construireUriRechercheAttractions(terme);

    compteurBilan.enregistrerAppel();
    try {
      final Map<String, Object> data = restClient.get().uri(uri).retrieve().body(Map.class);
      if (data == null) {
        return null;
      }
      final List<Map<String, Object>> attractions =
          (List<Map<String, Object>>) data.get("attractions");
      if (attractions == null || attractions.isEmpty()) {
        return null;
      }

      final Map<String, Object> exact =
          selectionnerAttraction(attractions, terme, identiteConfirmee, autresNomsConnus);
      return exact != null ? extraireAttractionId(exact) : null;
    } catch (ResourceAccessException e) {
      // Panne réseau : un artiste introuvable ne doit pas être confondu avec une source
      // injoignable (ses concerts compteraient sinon une absence).
      compteurBilan.enregistrerErreur(CauseEchecHttp.depuis(e));
      LOG.warn("Eventim injoignable (attractions) terme={} : {}", terme, e.getMessage());
      throw e;
    } catch (Exception e) {
      compteurBilan.enregistrerErreur(CauseEchecHttp.depuis(e));
      LOG.warn("Eventim attractions erreur terme={} : {}", terme, e.getMessage());
      return null;
    }
  }

  /**
   * Indique si l'identifiant enregistré désigne chez Eventim un autre artiste
   * (présent dans les résultats de la recherche par nom, sous un autre nom).
   * @param terme nom de l'artiste suivi
   * @param attractionId identifiant enregistré
   * @param identiteConfirmee identité de l'artiste confirmée par Deezer ou
   *     Wikidata : même règle que la recherche d'attraction du fetch
   * @param autresNomsConnus libellé et alias connus de l'artiste
   * @return {@code true} si l'identifiant est prouvé être celui d'un autre
   */
  @SuppressWarnings("unchecked")
  /* package-private : appelé par EventimSource (même package). */
  boolean attractionDUnAutreArtiste(
      final String terme,
      final String attractionId,
      final boolean identiteConfirmee,
      final List<String> autresNomsConnus) {
    final URI uri = construireUriRechercheAttractions(terme);
    compteurBilan.enregistrerAppel();
    try {
      final Map<String, Object> data = restClient.get().uri(uri).retrieve().body(Map.class);
      final List<Map<String, Object>> attractions =
          data == null ? null : (List<Map<String, Object>>) data.get("attractions");
      if (attractions == null) {
        return false;
      }
      final String termeNorm = Normalisation.normaliserTexte(terme);
      final Set<String> autresNomsNorm =
          autresNomsConnus.stream()
              .filter(Objects::nonNull)
              .map(Normalisation::normaliserTexte)
              .filter(n -> !n.isEmpty() && !n.equals(termeNorm))
              .collect(Collectors.toSet());
      return attractions.stream()
          .filter(a -> attractionId.equals(extraireAttractionId(a)))
          .findFirst()
          .map(a -> nomDifferent(a, terme, termeNorm, identiteConfirmee, autresNomsNorm))
          .orElse(false);
    } catch (ResourceAccessException e) {
      compteurBilan.enregistrerErreur(CauseEchecHttp.depuis(e));
      throw e;
    } catch (Exception e) {
      compteurBilan.enregistrerErreur(CauseEchecHttp.depuis(e));
      LOG.warn("Eventim attractions erreur terme={} : {}", terme, e.getMessage());
      return false;
    }
  }

  private static boolean nomDifferent(
      final Map<String, Object> attraction,
      final String terme,
      final String termeNorm,
      final boolean identiteConfirmee,
      final Set<String> autresNomsNorm) {
    final String nomNorm = Normalisation.normaliserTexte(nomAttraction(attraction));
    return !estCorrespondanceExacte(nomNorm, termeNorm)
        && !autresNomsNorm.contains(nomNorm)
        && !estContainmentEligible(terme, termeNorm, nomNorm, identiteConfirmee, autresNomsNorm);
  }

  private static String nomAttraction(final Map<String, Object> attraction) {
    return (String) attraction.getOrDefault(CLE_NOM, "");
  }

  private URI construireUriRechercheAttractions(final String terme) {
    return UriSources.encoder(
        UriComponentsBuilder.fromUriString(baseUrl + "/v1/attractions")
            .queryParam(PARAM_WEB_ID, webId)
            .queryParam(PARAM_LANGUE, "fr")
            .queryParam("search_term", terme)
            .queryParam("top", 5));
  }

  /**
   * Meilleure correspondance : égalité exacte d'abord, puis containment,
   * restreint aux autres noms connus si l'identité est confirmée.
   * @return l'attraction retenue, ou null si aucune correspondance
   */
  // La map représente une attraction unique (un enregistrement), pas une
  // collection à agréger — l'appelant teste explicitement null pour distinguer
  // "aucune correspondance" d'une map vide.
  @SuppressWarnings("PMD.ReturnEmptyCollectionRatherThanNull")
  private Map<String, Object> selectionnerAttraction(
      final List<Map<String, Object>> attractions,
      final String terme,
      final boolean identiteConfirmee,
      final List<String> autresNomsConnus) {
    final String termeNorm = Normalisation.normaliserTexte(terme);
    final Set<String> autresNomsNorm =
        autresNomsConnus.stream()
            .filter(Objects::nonNull)
            .map(Normalisation::normaliserTexte)
            .filter(n -> !n.isEmpty() && !n.equals(termeNorm))
            .collect(Collectors.toSet());
    Map<String, Object> parContainment = null;
    for (final Map<String, Object> a : attractions) {
      final String nomNorm = Normalisation.normaliserTexte((String) a.getOrDefault(CLE_NOM, ""));
      if (estCorrespondanceExacte(nomNorm, termeNorm)) {
        return a;
      }
      if (parContainment == null
          && estContainmentEligible(terme, termeNorm, nomNorm, identiteConfirmee, autresNomsNorm)) {
        parContainment = a;
      }
    }
    if (parContainment != null) {
      journaliserContainmentAccepte(terme, parContainment);
      return parContainment;
    }
    journaliserAucuneCorrespondance(terme, attractions);
    return null;
  }

  // Egalité sans espaces : couvre une contraction de nom propre ("N'Dour" → "n
  // dour") qu'une source omet ("NDour"). Non gaté par identiteConfirmee, plus
  // strict que le containment.
  private static boolean estCorrespondanceExacte(final String nomNorm, final String termeNorm) {
    return nomNorm.equals(termeNorm) || UtilitairesChaine.estEgaliteSansEspaces(nomNorm, termeNorm);
  }

  // Accepte "terme" contenu dans le nom Eventim (ex. "-M-" dans "-M- Matthieu
  // Chedid"). Pour une identité déjà confirmée, le reste du nom doit être un
  // autre nom connu de l'artiste : un containment libre a accepté à répétition
  // un mauvais rapprochement ("Furies" dans "Love & Furies"). Seuil sur la
  // longueur brute pour éviter les faux positifs sur un seul caractère.
  private static boolean estContainmentEligible(
      final String terme,
      final String termeNorm,
      final String nomNorm,
      final boolean identiteConfirmee,
      final Set<String> autresNomsNorm) {
    if (terme.length() < 2 || termeNorm.isEmpty() || !nomNorm.contains(termeNorm)) {
      return false;
    }
    if (!identiteConfirmee) {
      return true;
    }
    final String reste =
        (" " + nomNorm + " ").replaceFirst(" " + Pattern.quote(termeNorm) + " ", " ").strip();
    return autresNomsNorm.contains(reste);
  }

  private void journaliserContainmentAccepte(
      final String terme, final Map<String, Object> attraction) {
    final String nomRetourne = (String) attraction.getOrDefault(CLE_NOM, "");
    LOG.info(
        "Eventim: correspondance par containment pour terme={} — résultat={} accepté",
        terme,
        nomRetourne);
    verrouContainments.lock();
    try {
      containmentsAcceptes.add("terme=" + terme + " → resultat=" + nomRetourne);
    } finally {
      verrouContainments.unlock();
    }
  }

  private void journaliserAucuneCorrespondance(
      final String terme, final List<Map<String, Object>> attractions) {
    final String nomRetourne = (String) attractions.get(0).getOrDefault(CLE_NOM, "");
    // Refus protecteur attendu (homonyme, hommage) : information, pas anomalie.
    LOG.info(
        "Eventim: aucune correspondance exacte pour terme={} — premier résultat={} ignoré",
        terme,
        nomRetourne);
  }

  private static String extraireAttractionId(final Map<String, Object> attraction) {
    Object id = attraction.get("attractionId");
    if (id == null) {
      id = attraction.get("id");
    }
    return id != null ? String.valueOf(id) : null;
  }
}
