/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.miseajour;

import fr.strapontin.exploitation.Etape;
import fr.strapontin.exploitation.EtatMiseAJour;
import fr.strapontin.exploitation.Resultat;
import fr.strapontin.exploitation.Version;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;

/**
 * Suivi d'une mise à jour : à chaque étape, l'état est réécrit d'un bloc
 * dans le dossier d'état pour que l'application l'affiche.
 *
 * @since 3.2.0
 * @author Kwaite
 */
public final class SuiviEtat {

  private static final int MESSAGE_MAX = 500;

  private final Path dossier;
  private final Clock horloge;
  private String versionCible;
  private Instant debut;
  private Etape etapeCourante;

  /**
   * @param dossier dossier d'état, qui existe déjà
   * @param horloge source de l'heure
   */
  public SuiviEtat(final Path dossier, final Clock horloge) {
    this.dossier = dossier;
    this.horloge = horloge;
  }

  /**
   * Ouvre une mise à jour : résultat {@code EN_COURS}, fin effacée.
   *
   * @param cible version en cours d'installation
   */
  public void demarrer(final Version cible) {
    this.versionCible = cible.toString();
    this.debut = horloge.instant();
    this.etapeCourante = Etape.VERIFICATION;
    ecrire(Resultat.EN_COURS, null, null);
  }

  /**
   * Signale l'étape en cours.
   *
   * @param nouvelle étape qui commence
   */
  public void etape(final Etape nouvelle) {
    this.etapeCourante = nouvelle;
    ecrire(Resultat.EN_COURS, null, null);
  }

  /** Clôt la mise à jour réussie. */
  public void reussir() {
    ecrire(Resultat.REUSSI, horloge.instant(), null);
  }

  /**
   * Clôt la mise à jour en échec, à l'étape où elle s'est arrêtée.
   *
   * @param message motif en français, tronqué à 500 caractères
   */
  public void echouer(final String message) {
    final String borne =
        message.length() > MESSAGE_MAX ? message.substring(0, MESSAGE_MAX) : message;
    ecrire(Resultat.ECHEC, horloge.instant(), borne);
  }

  private void ecrire(final Resultat resultat, final Instant fin, final String message) {
    try {
      new EtatMiseAJour(resultat, etapeCourante, versionCible, debut, fin, message).ecrire(dossier);
    } catch (final UncheckedIOException e) {
      throw new InstallationException("Écriture de l'état impossible", e);
    }
  }
}
