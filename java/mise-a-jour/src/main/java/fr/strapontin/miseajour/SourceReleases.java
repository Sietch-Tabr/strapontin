/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.miseajour;

import fr.strapontin.exploitation.Version;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Origine des releases : lit une release et télécharge ses fichiers.
 *
 * @since 3.2.0
 * @author Kwaite
 */
public interface SourceReleases {

  /**
   * Cherche la release d'une version.
   *
   * @param version version demandée
   * @return la release, vide si l'étiquette n'existe pas
   * @throws SourceInaccessibleException si la source ne répond pas
   */
  Optional<ReleaseGithub> release(Version version);

  /**
   * Télécharge un fichier joint dans un dossier de travail.
   *
   * @param asset fichier à télécharger
   * @param dossier dossier de destination, qui existe déjà
   * @return le fichier écrit et son empreinte
   * @throws SourceInaccessibleException si le téléchargement est refusé
   */
  Telechargement telecharger(Asset asset, Path dossier);
}
