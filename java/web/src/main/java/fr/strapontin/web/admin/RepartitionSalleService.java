/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.admin;

import fr.strapontin.core.IdentifiantLieuEventim;
import fr.strapontin.core.Normalisation;
import fr.strapontin.web.ErreurMetier;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Répartition d'une salle qui mélange plusieurs lieux réels : les événements publiés sous les
 * libellés choisis passent à une autre salle, existante ou créée, avec les lieux Eventim
 * correspondants.
 *
 * <p>Seuls les événements dont toutes les observations portent ces libellés sont déplacés ; un
 * événement observé aussi sous un autre libellé (mixte) reste en place et est compté. Un
 * événement déjà présent dans la salle cible y est fusionné. Les lieux Ticketmaster ne sont pas
 * déplacés (aucun libellé conservé pour eux).
 *
 * @since 3.0.2
 * @author Kwaite
 */
@Service
public class RepartitionSalleService {

  private final RepartitionSalleRepository repository;
  private final LogAdminService logAdminService;

  /**
   * Libellé sous lequel des événements d'une salle sont publiés.
   *
   * @param libelle libellé du lieu chez la source
   * @param nbEvenements nombre d'événements de la salle observés sous ce libellé
   */
  public record LibelleSource(String libelle, long nbEvenements) {}

  /**
   * Bilan d'une répartition.
   *
   * @param cibleId salle ayant reçu les événements
   * @param evenementsDeplaces événements déplacés vers la cible
   * @param evenementsFusionnes événements fusionnés dans un événement déjà présent dans la cible
   * @param evenementsMixtes événements laissés en place, observés aussi sous un autre libellé
   * @param lieuxEventimDeplaces lieux Eventim rattachés à la cible
   */
  public record ResultatRepartition(
      UUID cibleId,
      int evenementsDeplaces,
      int evenementsFusionnes,
      int evenementsMixtes,
      int lieuxEventimDeplaces) {}

  /**
   * @param repository accès SQL
   * @param logAdminService journalisation de chaque répartition
   */
  public RepartitionSalleService(
      final RepartitionSalleRepository repository, final LogAdminService logAdminService) {
    this.repository = repository;
    this.logAdminService = logAdminService;
  }

  /**
   * @param salleId salle à examiner
   * @return libellés source de ses événements, du plus fréquent au moins fréquent
   */
  public List<LibelleSource> listerLibelles(final UUID salleId) {
    return repository.listerLibelles(salleId);
  }

  /**
   * Déplace vers une salle cible les événements publiés sous les libellés choisis.
   *
   * @param salleId salle à répartir
   * @param requete libellés, et salle cible existante ou nom de la salle à créer
   * @return bilan de la répartition
   * @throws ErreurMetier si la salle est inconnue, la cible invalide ou aucun libellé choisi
   */
  @Transactional
  public ResultatRepartition repartir(final UUID salleId, final RequeteRepartitionSalle requete) {
    final Map<String, Object> salle = repository.lireSalle(salleId);
    if (salle == null) {
      throw new ErreurMetier("Salle introuvable.", 404);
    }
    final UUID cibleId = resoudreCible(salleId, salle, requete);
    final List<String> libelles = requete.libelles();

    final int mixtes = repository.compterEvenementsMixtes(salleId, libelles);
    final List<UUID> evenements = repository.evenementsEntierementLibelles(salleId, libelles);
    final int fusionnes =
        evenements.isEmpty() ? 0 : repository.fusionnerEvenementsEnDoublon(evenements, cibleId);
    final int deplaces =
        evenements.isEmpty() ? 0 : repository.deplacerEvenements(evenements, cibleId);
    final int lieux = deplacerLieuxEventim(salleId, libelles, cibleId);
    repository.completerPositionDepuisMappings(cibleId);
    repository.leverContamination(salleId);

    logAdminService.logAdmin(
        "info",
        "admin",
        "répartition de salle",
        Map.of(
            "salleId",
            salleId.toString(),
            "cibleId",
            cibleId.toString(),
            "libelles",
            String.join(" | ", libelles),
            "evenementsDeplaces",
            deplaces,
            "evenementsFusionnes",
            fusionnes,
            "evenementsMixtes",
            mixtes));
    return new ResultatRepartition(cibleId, deplaces, fusionnes, mixtes, lieux);
  }

  private UUID resoudreCible(
      final UUID salleId, final Map<String, Object> salle, final RequeteRepartitionSalle requete) {
    if (requete.cibleId() != null) {
      if (requete.cibleId().equals(salleId) || repository.lireSalle(requete.cibleId()) == null) {
        throw new ErreurMetier("Salle cible invalide.");
      }
      return requete.cibleId();
    }
    final String nom = requete.nouvelleSalleNom().strip();
    return repository.creerSalle(
        nom, Normalisation.normaliserTexte(nom), (String) salle.get("dept"));
  }

  private int deplacerLieuxEventim(
      final UUID salleId, final List<String> libelles, final UUID cibleId) {
    final Set<String> nomsNormalises =
        libelles.stream().map(Normalisation::normaliserTexte).collect(Collectors.toSet());
    int deplaces = 0;
    for (final Map<String, Object> mapping : repository.mappingsEventim(salleId)) {
      final String identifiant = (String) mapping.get("identifiant_externe");
      final String libelleSource = (String) mapping.get("libelle_source");
      if ((libelleSource != null && libelles.contains(libelleSource))
          || nomsNormalises.contains(IdentifiantLieuEventim.nom(identifiant))) {
        repository.deplacerMapping(identifiant, cibleId);
        deplaces++;
      }
    }
    return deplaces;
  }
}
