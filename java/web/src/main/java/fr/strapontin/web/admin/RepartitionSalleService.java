/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.admin;

import fr.strapontin.core.IdentifiantLieuEventim;
import fr.strapontin.core.Normalisation;
import fr.strapontin.web.ErreurMetier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Répartition d'une salle mélangeant plusieurs lieux : événements des
 * libellés choisis et lieux choisis (toutes sources) passent à une autre
 * salle, un événement mixte reste en place ; les deux salles sont ensuite
 * déclarées distinctes.
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
   * Lieu d'une source rattaché à la salle.
   *
   * @param identifiant identifiant préfixé ({@code source:identifiant})
   * @param source source du lieu
   * @param nom nom du lieu chez la source (libellé ou nom de l'identifiant)
   * @param ville ville du lieu chez la source, si connue
   */
  public record LieuSource(String identifiant, String source, String nom, String ville) {}

  /**
   * Bilan : événements déplacés, fusionnés dans un événement existant de la
   * cible, laissés en place (mixtes), lieux déplacés et lieux restés sur la
   * salle d'origine.
   */
  public record ResultatRepartition(
      UUID cibleId,
      int evenementsDeplaces,
      int evenementsFusionnes,
      int evenementsMixtes,
      int lieuxDeplaces,
      List<String> lieuxRestes) {}

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
   * @param salleId salle à examiner
   * @return lieux de toutes les sources rattachés à la salle
   */
  public List<LieuSource> listerLieux(final UUID salleId) {
    return repository.lieux(salleId).stream()
        .map(
            lieu ->
                new LieuSource(
                    lieu.get("source") + ":" + lieu.get("identifiant_externe"),
                    (String) lieu.get("source"),
                    nomDuLieu(lieu),
                    (String) lieu.get("ville_source")))
        .toList();
  }

  /**
   * @param requete libellés, lieux à déplacer (tous ceux dont le nom porte un
   *     libellé choisi si absent), et salle cible existante ou nom à créer
   * @return bilan de la répartition
   * @throws ErreurMetier salle inconnue, cible invalide, aucun libellé ou lieu
   *     étranger à la salle
   */
  @Transactional
  public ResultatRepartition repartir(final UUID salleId, final RequeteRepartitionSalle requete) {
    final Map<String, Object> salle =
        repository
            .lireSalle(salleId)
            .orElseThrow(() -> new ErreurMetier("Salle introuvable.", 404));
    final List<LieuSource> lieux = listerLieux(salleId);
    final List<String> aDeplacer = lieuxADeplacer(lieux, requete);
    final UUID cibleId = resoudreCible(salleId, salle, requete);
    final List<String> libelles = requete.libelles();

    final int mixtes = repository.compterEvenementsMixtes(salleId, libelles);
    final List<UUID> evenements = repository.evenementsEntierementLibelles(salleId, libelles);
    final int fusionnes =
        evenements.isEmpty() ? 0 : repository.fusionnerEvenementsEnDoublon(evenements, cibleId);
    final int deplaces =
        evenements.isEmpty() ? 0 : repository.deplacerEvenements(evenements, cibleId);
    aDeplacer.forEach(identifiant -> repository.deplacerMapping(identifiant, cibleId));
    repository.completerPositionDepuisMappings(cibleId);
    repository.leverContamination(salleId);
    repository.declarerDistinctes(salleId, cibleId);

    final List<String> restes =
        lieux.stream()
            .map(LieuSource::identifiant)
            .filter(identifiant -> !aDeplacer.contains(identifiant))
            .toList();
    final Map<String, Object> meta = new HashMap<>();
    meta.put("salleId", salleId.toString());
    meta.put("cibleId", cibleId.toString());
    meta.put("libelles", String.join(" | ", libelles));
    meta.put("evenementsDeplaces", deplaces);
    meta.put("evenementsFusionnes", fusionnes);
    meta.put("evenementsMixtes", mixtes);
    meta.put("lieuxDeplaces", aDeplacer);
    meta.put("lieuxRestes", restes);
    logAdminService.logAdmin("info", "admin", "répartition de salle", meta);
    return new ResultatRepartition(cibleId, deplaces, fusionnes, mixtes, aDeplacer.size(), restes);
  }

  private UUID resoudreCible(
      final UUID salleId, final Map<String, Object> salle, final RequeteRepartitionSalle requete) {
    if (requete.cibleId() != null) {
      if (requete.cibleId().equals(salleId) || repository.lireSalle(requete.cibleId()).isEmpty()) {
        throw new ErreurMetier("Salle cible invalide.");
      }
      return requete.cibleId();
    }
    final String nom = requete.nouvelleSalleNom().strip();
    return repository.creerSalle(
        nom, Normalisation.normaliserTexte(nom), (String) salle.get("dept"));
  }

  // Choix explicite de l'admin, sinon les lieux dont le nom porte un libellé choisi.
  private static List<String> lieuxADeplacer(
      final List<LieuSource> lieux, final RequeteRepartitionSalle requete) {
    final Set<String> rattaches =
        lieux.stream().map(LieuSource::identifiant).collect(Collectors.toSet());
    if (requete.lieux() != null) {
      for (final String identifiant : requete.lieux()) {
        if (!rattaches.contains(identifiant)) {
          throw new ErreurMetier("Ce lieu n'est pas rattaché à la salle : " + identifiant);
        }
      }
      return List.copyOf(requete.lieux());
    }
    final Set<String> nomsNormalises =
        requete.libelles().stream().map(Normalisation::normaliserTexte).collect(Collectors.toSet());
    final List<String> choisis = new ArrayList<>();
    for (final LieuSource lieu : lieux) {
      if (lieu.nom() != null
          && nomsNormalises.contains(Normalisation.normaliserTexte(lieu.nom()))) {
        choisis.add(lieu.identifiant());
      }
    }
    return choisis;
  }

  // Le libellé publié prime ; un lieu Eventim interne porte son nom dans
  // son identifiant.
  private static String nomDuLieu(final Map<String, Object> lieu) {
    if (lieu.get("libelle_source") instanceof String libelle && !libelle.isBlank()) {
      return libelle;
    }
    final String identifiant = (String) lieu.get("identifiant_externe");
    return "eventim".equals(lieu.get("source")) && identifiant.startsWith("internal:")
        ? IdentifiantLieuEventim.nom(identifiant)
        : null;
  }
}
