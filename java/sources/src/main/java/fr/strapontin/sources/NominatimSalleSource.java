/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.sources;

import fr.strapontin.core.BilanFetch;
import fr.strapontin.core.CompteurBilanFetch;
import fr.strapontin.core.DistanceGeo;
import fr.strapontin.core.NomsDeLieu;
import fr.strapontin.core.Normalisation;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Identifiant Wikidata d'une salle lu sur l'objet OpenStreetMap
 * correspondant, trouvé par Nominatim autour de sa position connue. Les
 * salles sans position, ou que Nominatim ne trouve pas, restent éligibles à
 * la recherche Wikidata directe ({@link WikidataSalleSource}).
 * @since 3.0.2
 * @author Kwaite
 */
public class NominatimSalleSource implements SourceEnrichissementSalle, SourceAvecBilanFetch {

  private static final Logger LOG = LoggerFactory.getLogger(NominatimSalleSource.class);

  // Rayon d'un même lieu à la résolution des salles par position.
  private static final double RAYON_MEME_LIEU_METRES = 300;
  private static final Pattern SEPARATEUR_NOM = Pattern.compile("\\s[-–(]\\s?");

  private final ClientNominatim client;
  private final CompteurBilanFetch compteurBilan = new CompteurBilanFetch();

  /**
   * @param client client Nominatim partagé (cadencé à une requête par seconde)
   */
  public NominatimSalleSource(final ClientNominatim client) {
    this.client = Objects.requireNonNull(client, "client");
  }

  @Override
  public String nom() {
    return "nominatim";
  }

  @Override
  public BilanFetch preleverBilan() {
    return compteurBilan.preleverBilan();
  }

  @Override
  public Map<UUID, EnrichissementSalle> enrichirLot(final List<SalleAEnrichir> salles) {
    final Map<UUID, EnrichissementSalle> resultats = new HashMap<>();
    for (final SalleAEnrichir salle : salles) {
      if (salle.wikidataId() != null || salle.latitude() == null || salle.longitude() == null) {
        continue;
      }
      chercher(salle).ifPresent(e -> resultats.put(salle.id(), e));
    }
    LOG.info("Nominatim : {} salle(s) enrichie(s) sur {}", resultats.size(), salles.size());
    return resultats;
  }

  // Nom complet, puis tronqué au séparateur (« LA SIRENE - Espace Musiques
  // Actuelles » n'est connu d'OpenStreetMap que comme « La Sirène »).
  private Optional<EnrichissementSalle> chercher(final SalleAEnrichir salle) {
    final Optional<EnrichissementSalle> complet = chercher(salle, salle.nom());
    final String tronque = SEPARATEUR_NOM.split(salle.nom(), 2)[0].strip();
    return complet.isPresent() || tronque.equals(salle.nom()) || tronque.isEmpty()
        ? complet
        : chercher(salle, tronque);
  }

  private Optional<EnrichissementSalle> chercher(final SalleAEnrichir salle, final String nom) {
    final ClientNominatim.Coordonnees position =
        new ClientNominatim.Coordonnees(salle.latitude(), salle.longitude());
    final String requete = salle.ville() != null ? nom + ", " + salle.ville() : nom;
    compteurBilan.enregistrerAppel();
    final ClientNominatim.ResultatRecherche resultat = client.rechercherLieux(requete, position);
    if (!resultat.succes()) {
      compteurBilan.enregistrerErreur();
      return Optional.empty();
    }
    if (resultat.lieux().isEmpty()) {
      compteurBilan.enregistrerReponseVide();
    }
    return resultat.lieux().stream()
        .filter(lieu -> lieu.etiquettes().get("wikidata") != null)
        .filter(lieu -> designeLaSalle(lieu, salle))
        .findFirst()
        .map(NominatimSalleSource::versEnrichissement);
  }

  // Même lieu : nom sans ambiguïté et objet à moins de 300 m de la salle.
  private static boolean designeLaSalle(
      final ClientNominatim.LieuOsm lieu, final SalleAEnrichir salle) {
    if (lieu.nom() == null) {
      return false;
    }
    final String nomNorm = Normalisation.normaliserTexte(lieu.nom());
    final boolean memeNom =
        nomNorm.equals(salle.nomNorm())
            || NomsDeLieu.designentFortementLeMemeLieu(
                nomNorm, salle.nomNorm(), salle.ville() + " " + salle.nom());
    return memeNom
        && DistanceGeo.metres(
                lieu.position().latitude(),
                lieu.position().longitude(),
                salle.latitude(),
                salle.longitude())
            <= RAYON_MEME_LIEU_METRES;
  }

  private static EnrichissementSalle versEnrichissement(final ClientNominatim.LieuOsm lieu) {
    final Map<String, String> etiquettes = lieu.etiquettes();
    return new EnrichissementSalle(
        etiquettes.get("wikidata"),
        etiquettes.get("name:fr"),
        etiquettes.get("addr:city"),
        etiquettes.get("website"),
        capacite(etiquettes.get("capacity")));
  }

  private static Integer capacite(final String valeur) {
    if (valeur == null) {
      return null;
    }
    final String chiffres = valeur.replaceAll("[^0-9]", "");
    return chiffres.isEmpty() || chiffres.length() > 9 ? null : Integer.valueOf(chiffres);
  }
}
