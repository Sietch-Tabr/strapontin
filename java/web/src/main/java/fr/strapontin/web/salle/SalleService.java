/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.salle;

import fr.strapontin.core.CorrespondanceSalle;
import fr.strapontin.core.Normalisation;
import fr.strapontin.web.ErreurMetier;
import fr.strapontin.web.RessourceDejaExistante;
import fr.strapontin.web.WorkerTriggerUtils;
import fr.strapontin.web.admin.LogAdminService;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.client.RestClient;

/**
 * Service de gestion des salles suivies (recherche en base + API
 * Ticketmaster, vérification du cap, déclenchement du worker).
 *
 * @since 2.1.0
 * @author Kwaite
 */
@Service
public class SalleService {

  private static final Logger LOG = LoggerFactory.getLogger(SalleService.class);
  private static final int CAP_SALLES = 200;
  private static final int LONGUEUR_MIN_RECHERCHE_EXTERNE = 2;
  private static final int NB_MIN_RESULTATS_BASE_AVANT_EXTERNE = 3;

  private final SalleRepository salleRepository;
  private final RestClient restClient;
  private final LogAdminService logAdminService;
  private final SalleRechercheNominatimService salleRechercheNominatimService;
  private final SalleRechercheTicketmasterService salleRechercheTicketmasterService;
  private SalleService self;

  /**
   * @param salleRepository accès aux données salles
   * @param restClient client HTTP partagé (timeout 5s/10s défini dans {@link
   *     fr.strapontin.web.WebConfiguration})
   * @param logAdminService journalisation des suppressions dans {@code logs_admin}
   * @param salleRechercheNominatimService recherche de salles via Nominatim (OSM)
   * @param salleRechercheTicketmasterService recherche via l'API Ticketmaster
   */
  public SalleService(
      final SalleRepository salleRepository,
      final RestClient restClient,
      final LogAdminService logAdminService,
      final SalleRechercheNominatimService salleRechercheNominatimService,
      final SalleRechercheTicketmasterService salleRechercheTicketmasterService) {
    this.salleRepository = salleRepository;
    this.restClient = restClient;
    this.logAdminService = logAdminService;
    this.salleRechercheNominatimService = salleRechercheNominatimService;
    this.salleRechercheTicketmasterService = salleRechercheTicketmasterService;
  }

  /**
   * Injection par setter de la référence proxifiée (proxy AOP requis pour que
   * {@link #mettreAJourWikidataIdEnSavepoint} bénéficie de la propagation
   * {@code NESTED} — un appel direct via {@code this} l'ignorerait).
   * @param self référence proxifiée de ce bean ({@code @Lazy})
   */
  @Autowired
  public void setSelf(@Lazy final SalleService self) {
    this.self = self;
  }

  /**
   * Liste les salles suivies, avec suggestions si {@code q} est fourni :
   * complète la base avec Nominatim puis Ticketmaster (moins de 3, puis 2
   * résultats), fusionne une salle retournée par les deux (osmId + tmVenueId).
   * @param userId identifiant de l'utilisateur
   * @param terme terme de recherche (optionnel, min 2 caractères)
   * @return la liste des salles et les suggestions
   */
  public ReponseSalles lister(final String userId, final String terme) {
    final String recherche = terme.strip().substring(0, Math.min(terme.strip().length(), 100));

    final List<SalleUtilisateur> salles = salleRepository.listerPourUtilisateur(userId);

    List<ResultatSalle> resultats = List.of();
    boolean dejaLaSuivie = false;

    if (recherche.length() >= LONGUEUR_MIN_RECHERCHE_EXTERNE) {
      final Set<String> sallesDejaAjoutees =
          salles.stream().map(s -> normaliserSalleCle(s.nom())).collect(Collectors.toSet());

      final List<Map<String, Object>> enBase =
          salleRepository.rechercherEnBase("%" + normaliserSalleCle(recherche) + "%");

      final List<ResultatSalle> listeResultats =
          enBase.stream()
              .filter(
                  r -> !sallesDejaAjoutees.contains(normaliserSalleCle((String) r.get("salle"))))
              .map(
                  r -> {
                    final String digitickSlug = (String) r.get("digitick_slug");
                    final String src = digitickSlug != null ? "base-digitick" : "base";
                    return new ResultatSalle(
                        (String) r.get("salle"), (String) r.get("ville"), null, digitickSlug, src);
                  })
              .collect(Collectors.toCollection(ArrayList::new));

      completerAvecSourcesExternes(listeResultats, sallesDejaAjoutees, recherche);

      resultats = listeResultats;
      final String rechercheNorm = normaliserSalleCle(recherche);
      dejaLaSuivie =
          SalleSuggestions.contientCorrespondancePartielle(rechercheNorm, sallesDejaAjoutees);
    }

    return new ReponseSalles(salles, resultats, dejaLaSuivie);
  }

  /**
   * Complète {@code listeResultats} avec Nominatim/Ticketmaster si la base
   * n'a pas assez de résultats — interrogés en parallèle, l'attente totale
   * bornée par le plus lent des deux plutôt que leur somme.
   * @param listeResultats résultats déjà trouvés en base, complétés en place
   * @param sallesDejaAjoutees clés normalisées des salles déjà suivies
   * @param recherche terme de recherche
   */
  private void completerAvecSourcesExternes(
      final List<ResultatSalle> listeResultats,
      final Set<String> sallesDejaAjoutees,
      final String recherche) {
    if (listeResultats.size() >= NB_MIN_RESULTATS_BASE_AVANT_EXTERNE) {
      return;
    }
    final CompletableFuture<List<ResultatSalle>> futureNominatim =
        CompletableFuture.supplyAsync(() -> salleRechercheNominatimService.rechercher(recherche));
    final CompletableFuture<List<ResultatSalle>> futureTm =
        CompletableFuture.supplyAsync(
            () -> salleRechercheTicketmasterService.rechercher(recherche));
    final List<ResultatSalle> resultatsExternes = new ArrayList<>(futureNominatim.join());
    resultatsExternes.addAll(futureTm.join());
    for (final ResultatSalle r : resultatsExternes) {
      if (sallesDejaAjoutees.contains(normaliserSalleCle(r.salle()))) {
        continue;
      }
      SalleSuggestions.fusionnerOuAjouter(listeResultats, r);
    }
  }

  /**
   * Ajoute une salle (délégation vers {@link #ajouter(DonneesAjoutSalle)}).
   * @param userId identifiant de l'utilisateur
   * @param nom nom de la salle (non-vide, max 200 car.)
   * @param tmVenueId identifiant Ticketmaster (nullable)
   * @throws ErreurMetier si le nom est vide ou le cap de 200 salles atteint
   * @throws RessourceDejaExistante si la salle est déjà suivie
   */
  @Transactional
  public void ajouter(final String userId, final String nom, final String tmVenueId) {
    ajouter(new DonneesAjoutSalle(userId, nom, tmVenueId, null, null));
  }

  /**
   * Données d'ajout d'une salle à la liste de suivi.
   * @param userId identifiant de l'utilisateur
   * @param nom nom de la salle (non-vide, max 200 car.)
   * @param tmVenueId identifiant Ticketmaster, peut être {@code null}
   * @param osmId identifiant OSM {@code "type/id"}, peut être {@code null}
   * @param wikidataId Q-ID Wikidata, peut être {@code null}
   */
  public record DonneesAjoutSalle(
      String userId, String nom, String tmVenueId, String osmId, String wikidataId) {}

  /**
   * Ajoute une salle (normalise, vérifie doublon/cap, résout via {@link
   * #resoudreSalleId}). osmId/wikidataId fournis : persistés immédiatement
   * (conflit d'index rattrapé et loggé).
   * @param donnees données d'ajout (voir {@link DonneesAjoutSalle})
   * @throws ErreurMetier si le nom est vide ou le cap de 200 salles atteint
   * @throws RessourceDejaExistante si la salle est déjà suivie
   */
  @Transactional
  public void ajouter(final DonneesAjoutSalle donnees) {
    final String salleCle = normaliserSalleCle(donnees.nom());
    if (salleCle.isEmpty()) {
      throw new ErreurMetier("Nom de salle invalide.");
    }

    final UUID salleId = resoudreSalleId(donnees.nom(), salleCle);
    validerAjoutSalle(donnees.userId(), salleId);
    enregistrerSourcesSalle(
        salleId, donnees.nom(), donnees.tmVenueId(), donnees.osmId(), donnees.wikidataId());
    salleRepository.ajouterPourUtilisateur(donnees.userId(), salleId);

    executerApresCommit(salleId);
  }

  private void validerAjoutSalle(final String userId, final UUID salleId) {
    if (salleRepository.existePourUtilisateur(userId, salleId)) {
      throw new RessourceDejaExistante("Cette salle est déjà dans votre liste.");
    }
    if (salleRepository.compterPourUtilisateur(userId) >= CAP_SALLES) {
      throw new ErreurMetier("Limite de 200 salles atteinte.");
    }
  }

  private void enregistrerSourcesSalle(
      final UUID salleId,
      final String nom,
      final String tmVenueId,
      final String osmId,
      final String wikidataId) {
    if (tmVenueId != null) {
      salleRepository.enregistrerSalleSource(salleId, "ticketmaster", tmVenueId);
    }
    if (osmId != null && !osmId.isBlank()) {
      salleRepository.enregistrerSalleSource(salleId, "osm", osmId);
    }
    if (wikidataId != null && !wikidataId.isBlank()) {
      try {
        self.mettreAJourWikidataIdEnSavepoint(salleId, wikidataId);
      } catch (DuplicateKeyException e) {
        LOG.warn(
            "wikidataId déjà attribué à une autre salle — conflit ignoré salle={} wikidataId={}",
            nom,
            wikidataId);
      }
    }
  }

  /**
   * Diffère le déclenchement du worker après le commit (isolation READ
   * COMMITTED : sans ce délai, la salle ajoutée pourrait manquer son propre
   * cycle de fetch — même correctif qu'ArtisteService). Exécute immédiatement
   * hors transaction (tests unitaires).
   * @param salleId UUID de la salle ajoutée/créée dans la transaction en cours
   */
  private void executerApresCommit(final UUID salleId) {
    final Runnable action = () -> declencherWorkerParSources(salleId);
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.registerSynchronization(
          new TransactionSynchronization() {
            @Override
            public void afterCommit() {
              action.run();
            }
          });
    } else {
      action.run();
    }
  }

  /**
   * Met à jour le wikidataId sous savepoint {@code NESTED} (isole le conflit
   * d'index de {@link #ajouter}, sans try/catch : l'exception doit se
   * propager pour le ROLLBACK TO SAVEPOINT Spring).
   * @param salleId UUID de la salle
   * @param wikidataId Q-ID Wikidata à attribuer
   * @throws DuplicateKeyException si déjà attribué à une autre salle
   */
  @Transactional(propagation = Propagation.NESTED)
  /* package-private : requis pour auto-invocation via proxy self (NESTED). */
  void mettreAJourWikidataIdEnSavepoint(final UUID salleId, final String wikidataId) {
    salleRepository.mettreAJourWikidataId(salleId, wikidataId);
  }

  /**
   * Résout l'UUID d'une salle, réutilisant un doublon flou (même algorithme
   * que ReconciliateurSalles/WikidataSalleSource/{@link #fusionnerOuAjouter}).
   * Public : aussi utilisé par PreferencesService.importerSalles.
   * @param nom nom affiché tel que saisi
   * @param salleCle clé normalisée ({@link #normaliserSalleCle})
   * @return UUID de la salle existante ou nouvellement créée
   */
  public UUID resoudreSalleId(final String nom, final String salleCle) {
    final Set<String> tokens =
        Arrays.stream(nom.split("\\s+"))
            .map(SalleService::normaliserSalleCle)
            .filter(t -> t.length() >= 3)
            .collect(Collectors.toSet());
    final String nomNormCible = Normalisation.normaliserTexte(nom);
    for (final Map<String, Object> candidat : salleRepository.chercherCandidatsParCle(tokens)) {
      final String cleCandidate = (String) candidat.get("salle_cle");
      if (cleCandidate.equals(salleCle)) {
        return salleRepository.upsertSalle(salleCle, nom);
      }
      final String nomCandidat = (String) candidat.get("nom");
      if (CorrespondanceSalle.estDoublonSalle(
          nomNormCible, Normalisation.normaliserTexte(nomCandidat))) {
        return (UUID) candidat.get("id");
      }
    }
    return salleRepository.upsertSalle(salleCle, nom);
  }

  /**
   * Déclenche immédiatement un fetch pour une salle identifiée par son UUID.
   *
   * <p>Lit toutes les sources configurées dans {@code salle_sources} (TM,
   * Digitick…) et les transmet au worker en une seule requête.
   *
   * @param salleId UUID de la salle
   */
  public void declencherWorkerParSources(final UUID salleId) {
    final String identifiants = salleRepository.lireIdentifiantsSources(salleId);
    if (identifiants != null) {
      declencherWorkerSalle(identifiants);
    }
  }

  /**
   * Supprime une salle de la liste de suivi.
   *
   * @param userId identifiant de l'utilisateur
   * @param salleId UUID stable de la salle
   */
  public void supprimer(final String userId, final UUID salleId) {
    if (salleRepository.supprimerPourUtilisateur(userId, salleId) == 0) {
      return;
    }
    LOG.info("salle supprimée userId={} salleId={}", userId, salleId);
    logAdminService.logAdmin(
        "warn", "web", "salle supprimée", Map.of("userId", userId, "salleId", salleId.toString()));
  }

  /**
   * Crée une salle Digitick (upsert + slug + déclenche worker). Si le slug
   * est déjà attribué, lève 409 plutôt qu'une salle sans source réelle.
   * @param nom nom affiché de la salle
   * @param slug slug Digitick
   * @param ville ville, peut être {@code null}
   * @param dept numéro de département, peut être {@code null}
   */
  public record DonneesSalleDigitick(String nom, String slug, String ville, String dept) {}

  /**
   * @param donnees données de la salle Digitick à créer (voir
   *     {@link DonneesSalleDigitick} )
   * @return UUID stable de la salle créée ou retrouvée
   * @throws ErreurMetier 409 si le slug est déjà attribué à une autre salle
   */
  @Transactional
  public UUID creerSalleDigitick(final DonneesSalleDigitick donnees) {
    final String salleCle = normaliserSalleCle(donnees.nom());
    final UUID salleId =
        salleRepository.upsertSalleAvecLoc(
            salleCle, donnees.nom(), donnees.ville(), donnees.dept());
    salleRepository
        .trouverConflitDigitickSlug(donnees.slug(), salleId)
        .ifPresent(
            nomConflit -> {
              throw new ErreurMetier("Slug déjà utilisé par la salle " + nomConflit + ".", 409);
            });
    salleRepository.enregistrerSalleSource(salleId, "digitick", donnees.slug());
    executerApresCommit(salleId);
    return salleId;
  }

  /**
   * Met à jour les métadonnées d'une salle (nom, ville, département).
   *
   * @param salleId UUID de la salle
   * @param nom nouveau nom (null = inchangé)
   * @param ville nouvelle ville (null = inchangée)
   * @param dept nouveau numéro de département (null = inchangé)
   */
  public void mettreAJourSalle(
      final UUID salleId, final String nom, final String ville, final String dept) {
    salleRepository.mettreAJourSalle(salleId, nom, ville, dept);
  }

  /**
   * Retourne les identifiants sources CSV d'une salle (ex.
   * {@code "digitick:lempreinte,tm:rZ..."} ).
   *
   * @param salleId UUID de la salle
   * @return CSV d'identifiants ou {@code null} si aucun
   */
  public String lireIdentifiantsSources(final UUID salleId) {
    return salleRepository.lireIdentifiantsSources(salleId);
  }

  /**
   * Supprime une salle Digitick si elle n'est suivie par aucun utilisateur et
   * sans concerts.
   *
   * @param salleId UUID de la salle
   * @return {@code true} si supprimée, {@code false} si une contrainte bloque
   */
  public boolean supprimerSalleDigitick(final UUID salleId) {
    return salleRepository.supprimerSalleDigitick(salleId);
  }

  /**
   * Normalise un nom de salle en retirant accents, ponctuation et espaces (clé
   * stable).
   *
   * @param nom nom brut de la salle
   * @return identifiant normalisé (ex. {@code "olympia"} )
   */
  public static String normaliserSalleCle(final String nom) {
    final String nfd = Normalizer.normalize(nom.toLowerCase(Locale.ROOT), Normalizer.Form.NFD);
    return nfd.replaceAll("\\p{InCombiningDiacriticalMarks}", "").replaceAll("[^a-z0-9]", "");
  }

  private void declencherWorkerSalle(final String identifiants) {
    WorkerTriggerUtils.declencherWorkerAsync(
        restClient,
        LOG,
        "http://localhost:8081/trigger/salle?identifiants="
            + URLEncoder.encode(identifiants, StandardCharsets.UTF_8),
        "Déclenchement worker salle impossible identifiants=" + identifiants);
  }
}
