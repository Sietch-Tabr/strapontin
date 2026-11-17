/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.miseajour;

import fr.strapontin.exploitation.Demande;
import fr.strapontin.exploitation.EtatMiseAJour;
import fr.strapontin.exploitation.Version;
import fr.strapontin.web.ErreurMetier;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.info.BuildProperties;
import org.springframework.stereotype.Service;

/**
 * Mise à jour depuis l'administration, côté application : lit l'état écrit
 * par le module de mise à jour et dépose sa demande ; le module, root,
 * télécharge, contrôle et installe.
 *
 * @since 3.2.0
 * @author Kwaite
 */
@Service
public class MiseAJourService {

  private static final Logger LOG = LoggerFactory.getLogger(MiseAJourService.class);
  private static final Duration EN_COURS_PERIME = Duration.ofHours(1);

  private final ReleasesGithub releases;
  private final BuildProperties buildProperties;
  private final Path dossierDemandes;
  private final Path dossierEtat;
  private final Clock horloge;

  /**
   * @param releases releases installables
   * @param buildProperties version en service
   * @param dossierDemandes dossier surveillé par le module de mise à jour
   * @param dossierEtat dossier où le module écrit son état
   */
  @Autowired
  public MiseAJourService(
      final ReleasesGithub releases,
      final BuildProperties buildProperties,
      @Value("${DEMANDES_DIR:/var/opt/strapontin/demandes}") final String dossierDemandes,
      @Value("${ETAT_DIR:/var/opt/strapontin/etat}") final String dossierEtat) {
    this(
        releases,
        buildProperties,
        Path.of(dossierDemandes),
        Path.of(dossierEtat),
        Clock.systemUTC());
  }

  /* package-private : emplacements et horloge injectés par les tests. */
  MiseAJourService(
      final ReleasesGithub releases,
      final BuildProperties buildProperties,
      final Path dossierDemandes,
      final Path dossierEtat,
      final Clock horloge) {
    this.releases = releases;
    this.buildProperties = buildProperties;
    this.dossierDemandes = dossierDemandes;
    this.dossierEtat = dossierEtat;
    this.horloge = horloge;
  }

  /**
   * Ce que l'onglet affiche.
   *
   * @param totpActif vrai si l'administrateur a activé son TOTP
   * @return versions, dernier état et présence d'une demande
   */
  public EtatMiseAJourWeb etat(final boolean totpActif) {
    final boolean attente = demandeEnAttente();
    return new EtatMiseAJourWeb(
        installee().toString(),
        releases.plusRecente(installee()).map(Version::toString).orElse(null),
        EtatMiseAJour.lire(dossierEtat).orElse(null),
        attente,
        attente || miseAJourEnCours(),
        totpActif);
  }

  /**
   * Dépose la demande d'installation d'une version proposée.
   *
   * @param version version voulue
   * @param demandeur identifiant de l'administrateur, pour le journal du module
   * @throws ErreurMetier 400 version non proposée, 409 déjà en cours, 500
   */
  public void demander(final String version, final String demandeur) {
    final Optional<Version> proposee = releases.plusRecente(installee());
    if (proposee.isEmpty() || !proposee.get().toString().equals(version)) {
      throw new ErreurMetier("Cette version n'est pas proposée.");
    }
    if (demandeEnAttente() || miseAJourEnCours()) {
      throw new ErreurMetier("Une mise à jour est déjà demandée ou en cours.", 409);
    }
    try {
      new Demande(proposee.get(), demandeur, horloge.instant()).deposer(dossierDemandes);
    } catch (final UncheckedIOException e) {
      LOG.error("Dépôt de la demande de mise à jour impossible", e);
      final ErreurMetier erreur = new ErreurMetier("La demande n'a pas pu être déposée.", 500);
      erreur.initCause(e);
      throw erreur;
    }
  }

  private Version installee() {
    return Version.analyser(buildProperties.getVersion());
  }

  private boolean demandeEnAttente() {
    try {
      return Demande.enAttente(dossierDemandes);
    } catch (final UncheckedIOException e) {
      LOG.warn("Dossier des demandes illisible : {}", e.getMessage());
      return false;
    }
  }

  private boolean miseAJourEnCours() {
    return EtatMiseAJour.lire(dossierEtat)
        .filter(etat -> etat.enCoursDepuisMoinsDe(horloge.instant(), EN_COURS_PERIME))
        .isPresent();
  }
}
