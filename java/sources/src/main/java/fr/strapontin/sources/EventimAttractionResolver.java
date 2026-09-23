/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.sources;

import fr.strapontin.core.CompteurBilanFetch;
import fr.strapontin.core.Normalisation;
import fr.strapontin.core.UtilitairesChaine;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Résolution de l'attractionId Eventim correspondant à un nom d'artiste,
 * via l'API attractions — extrait d'{@link EventimSource}.
 * @since 2.9.1
 * @author Kwaite
 */
final class EventimAttractionResolver {

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
  @SuppressWarnings("unchecked")
  /* package-private : appelé par EventimSource (même package). */
  String rechercherAttractionId(final String terme, final boolean identiteConfirmee) {
    final String uri = construireUriRechercheAttractions(terme);

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
          selectionnerAttraction(attractions, terme, identiteConfirmee);
      return exact != null ? extraireAttractionId(exact) : null;
    } catch (Exception e) {
      compteurBilan.enregistrerErreur();
      LOG.warn("Eventim attractions erreur terme={} : {}", terme, e.getMessage());
      return null;
    }
  }

  private String construireUriRechercheAttractions(final String terme) {
    return UriComponentsBuilder.fromUriString(baseUrl + "/v1/attractions")
        .queryParam(PARAM_WEB_ID, webId)
        .queryParam(PARAM_LANGUE, "fr")
        .queryParam("search_term", terme)
        .queryParam("top", 5)
        .build()
        .toUriString();
  }

  /**
   * Sélectionne la meilleure correspondance : égalité exacte en priorité,
   * repli sur containment si l'identité n'est pas déjà confirmée.
   * @param attractions résultats bruts de l'API attractions Eventim
   * @param terme terme recherché
   * @param identiteConfirmee désactive le repli par containment si vrai
   * @return l'attraction retenue, ou null si aucune correspondance
   */
  // La map représente une attraction unique (un enregistrement), pas une
  // collection à agréger — l'appelant teste explicitement null pour distinguer
  // "aucune correspondance" d'une map vide.
  @SuppressWarnings("PMD.ReturnEmptyCollectionRatherThanNull")
  private Map<String, Object> selectionnerAttraction(
      final List<Map<String, Object>> attractions,
      final String terme,
      final boolean identiteConfirmee) {
    final String termeNorm = Normalisation.normaliserTexte(terme);
    Map<String, Object> parContainment = null;
    for (final Map<String, Object> a : attractions) {
      final String nomNorm = Normalisation.normaliserTexte((String) a.getOrDefault("name", ""));
      if (estCorrespondanceExacte(nomNorm, termeNorm)) {
        return a;
      }
      if (parContainment == null
          && estContainmentEligible(terme, termeNorm, nomNorm, identiteConfirmee)) {
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
  // Chedid") — seulement si l'identité n'est pas déjà confirmée par ailleurs :
  // sinon ce repli approximatif a fini par accepter à répétition un mauvais
  // rapprochement (ex. "Furies" dans "Love & Furies", deux entités distinctes).
  // Seuil sur la longueur brute pour éviter les faux positifs sur un seul
  // caractère.
  private static boolean estContainmentEligible(
      final String terme,
      final String termeNorm,
      final String nomNorm,
      final boolean identiteConfirmee) {
    return !identiteConfirmee
        && terme.length() >= 2
        && !termeNorm.isEmpty()
        && nomNorm.contains(termeNorm);
  }

  private void journaliserContainmentAccepte(
      final String terme, final Map<String, Object> attraction) {
    final String nomRetourne = (String) attraction.getOrDefault("name", "");
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
    final String nomRetourne = (String) attractions.get(0).getOrDefault("name", "");
    LOG.warn(
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
