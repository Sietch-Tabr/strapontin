/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.core;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/**
 * Issue d'un envoi push et règle de révocation partagées par tous les
 * émetteurs : un abonnement supprimé (404/410, définitif selon la
 * spécification Web Push) est révoqué au premier échec, tout autre échec
 * après {@value #SEUIL_ECHECS_CONSECUTIFS} envois consécutifs en échec.
 * @since 3.0.4
 * @author Kwaite
 */
public final class EchecPush {

  /** Envois consécutifs en échec au-delà desquels l'appareil est révoqué. */
  public static final int SEUIL_ECHECS_CONSECUTIFS = 5;

  /**
   * Incrémente le compteur d'un abonnement {@code :id}, date l'échec et
   * en garde le {@code :statut} HTTP (null sans réponse), puis renvoie
   * le compteur.
   */
  public static final String SQL_INCREMENTER_ECHECS =
      "UPDATE push_abonnements SET echecs_consecutifs = echecs_consecutifs + 1,"
          + " dernier_echec_le = now(), dernier_echec_statut = :statut"
          + " WHERE id = :id RETURNING echecs_consecutifs";

  /** Remet à zéro le compteur d'un abonnement {@code :id} après un envoi réussi. */
  public static final String SQL_REINITIALISER_ECHECS =
      "UPDATE push_abonnements SET echecs_consecutifs = 0, dernier_echec_le = NULL,"
          + " dernier_echec_statut = NULL"
          + " WHERE id = :id AND (echecs_consecutifs <> 0 OR dernier_echec_le IS NOT NULL)";

  /** Supprime l'abonnement {@code :id}. */
  public static final String SQL_SUPPRIMER_ABONNEMENT =
      "DELETE FROM push_abonnements WHERE id = :id";

  /** Date et cause du retrait sur le compte {@code :userId}, pour l'avertissement. */
  public static final String SQL_DATER_RETRAIT =
      "UPDATE user_config SET dernier_appareil_retire_le = now(),"
          + " cause_dernier_retrait_appareil = :cause WHERE user_id = :userId";

  /** Cause lisible d'un appareil dont le service push n'est pas pris en charge. */
  public static final String CAUSE_SERVICE_NON_PRIS_EN_CHARGE =
      "le service de notification de votre navigateur n'est pas pris en charge";

  private static final int LONGUEUR_MAX_RAISON = 200;
  private static final int OCTETS_MAX_CORPS = 2048;
  private static final Pattern URL = Pattern.compile("https?://\\S+");
  private static final Pattern ESPACES = Pattern.compile("\\s+");
  private static final int STATUT_INTROUVABLE = 404;
  private static final int STATUT_SUPPRIME = 410;
  private static final int STATUT_INTERDIT = 403;
  private static final int STATUT_TROP_VOLUMINEUX = 413;
  private static final int STATUT_TROP_DE_REQUETES = 429;
  private static final int STATUT_ERREUR_CLIENT = 400;
  private static final int STATUT_ERREUR_SERVEUR = 500;

  /** Issue d'un envoi. */
  public enum Issue {
    /** Notification acceptée par le service push. */
    SUCCES,
    /** Abonnement supprimé côté navigateur : révocation immédiate. */
    ABONNEMENT_SUPPRIME,
    /** Échec comptabilisé vers le seuil de révocation. */
    ECHEC
  }

  private EchecPush() {}

  /**
   * @param statut statut HTTP de la réponse du service push
   * @return issue de l'envoi
   */
  public static Issue interpreter(final int statut) {
    if (statut == STATUT_INTROUVABLE || statut == STATUT_SUPPRIME) {
      return Issue.ABONNEMENT_SUPPRIME;
    }
    if (statut >= 200 && statut < 300) {
      return Issue.SUCCES;
    }
    return Issue.ECHEC;
  }

  /**
   * @param echecs nombre d'échecs consécutifs après celui-ci
   * @return {@code true} si l'appareil doit être révoqué
   */
  public static boolean seuilAtteint(final Integer echecs) {
    return echecs != null && echecs >= SEUIL_ECHECS_CONSECUTIFS;
  }

  /**
   * Raison d'un échec pour les journaux : code HTTP et explication fournie
   * par le service push, sur une ligne, tronquée, URL retirées (le corps
   * peut citer l'endpoint).
   * @param statut statut HTTP, ou null pour une exception sans réponse
   * @param corps corps de la réponse ou message de l'exception, peut être null
   * @return raison lisible, jamais null
   */
  public static String raison(final Integer statut, final String corps) {
    final String prefixe = statut != null ? "HTTP " + statut : "sans réponse";
    if (corps == null || corps.isBlank()) {
      return prefixe;
    }
    String texte = ESPACES.matcher(URL.matcher(corps).replaceAll("[url]")).replaceAll(" ").strip();
    if (texte.length() > LONGUEUR_MAX_RAISON) {
      texte = texte.substring(0, LONGUEUR_MAX_RAISON) + "…";
    }
    return prefixe + " : " + texte;
  }

  /**
   * @param endpoint URL d'abonnement, peut être null
   * @return schéma et hôte seuls : le chemin identifie l'appareil chez le
   *     service push et ne doit pas apparaître dans les journaux
   */
  public static String masquerEndpoint(final String endpoint) {
    if (endpoint == null) {
      return "(aucun)";
    }
    try {
      final URI uri = new URI(endpoint);
      return uri.getHost() != null ? uri.getScheme() + "://" + uri.getHost() + "/…" : "(invalide)";
    } catch (URISyntaxException e) {
      return "(invalide)";
    }
  }

  /**
   * Cause d'une révocation en termes compréhensibles par l'utilisateur.
   * @param statut statut HTTP du dernier échec, ou null sans réponse
   * @return phrase complète, sans point final
   */
  public static String causeLisible(final Integer statut) {
    if (statut == null) {
      return "le service de notification de votre navigateur est resté injoignable à plusieurs"
          + " reprises";
    }
    if (statut == STATUT_INTROUVABLE || statut == STATUT_SUPPRIME) {
      return "votre navigateur a supprimé l'abonnement (autorisation retirée, données du site"
          + " effacées ou application désinstallée)";
    }
    if (statut == STATUT_INTERDIT) {
      return "le service de notification de votre navigateur a refusé les notifications à"
          + " plusieurs reprises (la cause peut être l'abonnement de cet appareil ou notre"
          + " application)";
    }
    if (statut == STATUT_TROP_VOLUMINEUX || statut == STATUT_TROP_DE_REQUETES) {
      return "le service de notification de votre navigateur a refusé les notifications à"
          + " plusieurs reprises";
    }
    if (statut >= STATUT_ERREUR_SERVEUR) {
      return "le service de notification de votre navigateur est resté indisponible à"
          + " plusieurs reprises";
    }
    return statut >= STATUT_ERREUR_CLIENT
        ? "le service de notification de votre navigateur n'accepte plus l'abonnement"
        : "l'abonnement n'a pas pu être utilisé";
  }

  /** Ouverture du corps d'une réponse, qui peut échouer ou ne rien renvoyer. */
  @FunctionalInterface
  public interface SourceCorps {
    /**
     * @return flux du corps, peut être null
     * @throws IOException si le corps est illisible
     */
    InputStream ouvrir() throws IOException;
  }

  /**
   * Lit le début du corps d'une réponse en erreur, où les services push
   * expliquent le refus ; une lecture impossible n'empêche pas de traiter
   * l'échec.
   * @param source ouverture du corps
   * @return corps tronqué, ou null s'il est absent ou illisible
   */
  public static String lireCorps(final SourceCorps source) {
    try (InputStream flux = source.ouvrir()) {
      return flux == null
          ? null
          : new String(flux.readNBytes(OCTETS_MAX_CORPS), StandardCharsets.UTF_8);
    } catch (IOException | RuntimeException e) {
      return null;
    }
  }
}
