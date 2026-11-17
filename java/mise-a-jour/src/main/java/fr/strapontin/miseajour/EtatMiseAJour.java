/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.miseajour;

import fr.strapontin.exploitation.Version;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import tools.jackson.databind.json.JsonMapper;

/**
 * État de la mise à jour, écrit dans {@code etat/mise-a-jour.json} pour que
 * l'application l'affiche. Chaque écriture remplace le fichier d'un bloc :
 * un lecteur voit toujours un état complet.
 *
 * @since 3.2.0
 * @author Kwaite
 */
public final class EtatMiseAJour {

  /** Nom du fichier d'état dans le dossier d'état. */
  public static final String FICHIER = "mise-a-jour.json";

  private static final int MESSAGE_MAX = 500;

  private final Path fichier;
  private final Clock horloge;
  private final JsonMapper mapper = new JsonMapper();
  private Version cible;
  private Instant debut;
  private Etape etapeCourante;

  /**
   * @param dossier dossier d'état, qui existe déjà
   * @param horloge source de l'heure
   */
  public EtatMiseAJour(final Path dossier, final Clock horloge) {
    this.fichier = dossier.resolve(FICHIER);
    this.horloge = horloge;
  }

  /**
   * Ouvre une mise à jour : résultat {@code EN_COURS}, fin effacée.
   *
   * @param versionCible version en cours d'installation
   */
  public void demarrer(final Version versionCible) {
    this.cible = versionCible;
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
    ecrire(Resultat.ECHEC, horloge.instant(), borner(message));
  }

  private void ecrire(final Resultat resultat, final Instant fin, final String message) {
    final Map<String, Object> contenu = new LinkedHashMap<>();
    contenu.put("resultat", resultat.name());
    contenu.put("etape", etapeCourante == null ? null : etapeCourante.name());
    contenu.put("versionCible", cible == null ? null : cible.toString());
    contenu.put("debut", debut == null ? null : debut.toString());
    contenu.put("fin", fin == null ? null : fin.toString());
    contenu.put("message", message);
    try {
      final Path temporaire = fichier.resolveSibling(FICHIER + ".tmp");
      Files.writeString(temporaire, mapper.writeValueAsString(contenu));
      // Lisible par l'utilisateur de l'application : l'état ne contient aucun secret.
      Files.setPosixFilePermissions(temporaire, PosixFilePermissions.fromString("rw-r--r--"));
      Files.move(
          temporaire, fichier, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } catch (final IOException e) {
      throw new InstallationException("Écriture de l'état impossible", e);
    }
  }

  private static String borner(final String message) {
    if (message == null) {
      return "";
    }
    return message.length() > MESSAGE_MAX ? message.substring(0, MESSAGE_MAX) : message;
  }
}
