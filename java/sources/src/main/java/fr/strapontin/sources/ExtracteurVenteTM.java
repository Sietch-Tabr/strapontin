/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.sources;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Extraction du statut de vente (offsale/presale/package) d'un événement
 * Ticketmaster brut — extrait de {@link MapeurEvenementTM} pour rester sous le
 * seuil de complexité cyclomatique de classe (50).
 *
 * @since 2.9.1
 * @author Kwaite
 */
final class ExtracteurVenteTM {

  private static final String CLE_NAME = "name";
  private static final String UNCHECKED = "unchecked";
  private static final String STATUT_OFFSALE = "offsale";
  private static final String STATUT_PRESALE = "presale";

  // TM n'expose aucun champ documenté distinguant un event "package" (offre
  // VIP/bundle, ex. package-angele-.../idpack/...) d'un event standard — repéré
  // uniquement par ce segment d'URL, constant sur tous les cas observés en
  // production. Un package n'est pas accessible à un achat de billetterie
  // standard : son statut de vente ne doit jamais être notifié comme une
  // prévente.
  private static final Pattern URL_PACKAGE_TM = Pattern.compile("(?i)/pack/");

  private ExtracteurVenteTM() {}

  /* package-private : appelé par MapeurEvenementTM (même package). */
  record InfosVenteTM(String statut, String presaleDebut, String presaleNom) {}

  /* package-private : appelé par MapeurEvenementTM (même package). */
  static boolean estEvenementPackage(final String url) {
    return url != null && URL_PACKAGE_TM.matcher(url).find();
  }

  // "offsale" recouvre deux situations distinctes chez TM : un concert
  // réellement complet, ou une vente qui n'a simplement pas encore ouvert
  // (prévente à venir, ou vente publique future) — seule la seconde doit être
  // requalifiée en "presale" plutôt que rester "offsale" à tort.
  @SuppressWarnings(UNCHECKED)
  /* package-private : appelé par MapeurEvenementTM (même package). */
  static InfosVenteTM extraireInfosVenteTM(
      final Map<String, Object> dates, final Map<String, Object> evt) {
    final Map<String, String> statusMap = (Map<String, String>) dates.get("status");
    String statut = statusMap != null ? statusMap.get("code") : null;

    final Map<String, Object> sales = (Map<String, Object>) evt.get("sales");
    final List<Map<String, Object>> presales =
        sales != null ? (List<Map<String, Object>>) sales.get("presales") : null;
    final Map<String, Object> presale =
        presales != null && !presales.isEmpty() ? presales.get(0) : null;
    String presaleDebut = presale != null ? (String) presale.get("startDateTime") : null;
    final String presaleNom = presale != null ? (String) presale.get(CLE_NAME) : null;

    if (STATUT_OFFSALE.equals(statut)) {
      final String presaleRequalifiee = requalifierOffsaleEnPresale(sales, presaleDebut);
      if (presaleRequalifiee != null) {
        statut = STATUT_PRESALE;
        presaleDebut = presaleRequalifiee;
      }
    }
    return new InfosVenteTM(statut, presaleDebut, presaleNom);
  }

  /**
   * Détermine si "offsale" doit être requalifié "presale" (détail :
   * CLAUDE.md, Statut de vente cross-source).
   * @param sales bloc "sales" brut de l'event TM
   * @param presaleDebut date de début de prévente déjà extraite, ou null
   * @return date à utiliser si requalification, null sinon (inchangé)
   */
  @SuppressWarnings(UNCHECKED)
  private static String requalifierOffsaleEnPresale(
      final Map<String, Object> sales, final String presaleDebut) {
    if (presaleDebut != null && estDansLeFutur(presaleDebut)) {
      return presaleDebut;
    }
    final Map<String, Object> ventePublique =
        sales != null ? (Map<String, Object>) sales.get("public") : null;
    final String ouverturePublique =
        ventePublique != null ? (String) ventePublique.get("startDateTime") : null;
    return ouverturePublique != null && estDansLeFutur(ouverturePublique)
        ? ouverturePublique
        : null;
  }

  /**
   * Indique si un horodatage ISO 8601 (avec offset ou {@code Z} ) est dans le
   * futur.
   *
   * @param iso horodatage à analyser
   * @return {@code true} si postérieur à maintenant, {@code false} si passé ou
   *     illisible
   */
  private static boolean estDansLeFutur(final String iso) {
    try {
      return OffsetDateTime.parse(iso).toInstant().isAfter(Instant.now());
    } catch (Exception e) {
      return false;
    }
  }
}
