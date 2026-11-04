/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker.persister;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import fr.strapontin.sources.enrichissement.ClientNominatim;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

/**
 * Tests unitaires de {@link ResolveurSalleParProximiteGps}.
 *
 * @since 3.0.0
 * @author Kwaite
 */
class ResolveurSalleParProximiteGpsTest {

  // Cas réel confirmé en production (#1239) : "Theatre Rive Gauche Paris 14" et "Theatre de
  // Poche Montparnasse Paris" sont deux théâtres parisiens distincts mais à moins de 500m l'un
  // de l'autre (quartier Montparnasse) — un chevauchement de token même faible ("paris") doit
  // empêcher le rattachement par seule proximité GPS.
  @Test
  void chercher_nomsPartageantUnTokenMemeFaible_neRattachePas() {
    final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    final ResolveurSalleParProximiteGps resolveur = new ResolveurSalleParProximiteGps(jdbc);

    final Map<String, Object> candidatProche = new HashMap<>();
    candidatProche.put("id", UUID.randomUUID());
    candidatProche.put("nom_norm", "theatre de poche montparnasse paris");
    candidatProche.put("latitude", 48.840783);
    candidatProche.put("longitude", 2.3243234);

    when(jdbc.queryForList(anyString(), any(SqlParameterSource.class)))
        .thenReturn(List.of(candidatProche));

    final UUID resultat =
        resolveur.chercher(
            new ClientNominatim.Coordonnees(48.8385, 2.3238),
            "Theatre Rive Gauche Paris 14",
            "75",
            "eventim");

    assertNull(resultat);
  }

  // Cas légitime déjà documenté (Ed&n/Espace Dollfus Noack) : deux noms vraiment sans aucun
  // rapport textuel (0 token commun) doivent toujours pouvoir se rattacher par proximité GPS.
  @Test
  void chercher_nomsSansAucunTokenCommun_rattache() {
    final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    final ResolveurSalleParProximiteGps resolveur = new ResolveurSalleParProximiteGps(jdbc);
    final UUID salleProche = UUID.randomUUID();

    final Map<String, Object> candidatProche = new HashMap<>();
    candidatProche.put("id", salleProche);
    candidatProche.put("nom_norm", "espace dollfus noack");
    candidatProche.put("latitude", 48.5734);
    candidatProche.put("longitude", 7.7521);

    when(jdbc.queryForList(anyString(), any(SqlParameterSource.class)))
        .thenReturn(List.of(candidatProche));

    final UUID resultat =
        resolveur.chercher(
            new ClientNominatim.Coordonnees(48.5734, 7.7521), "Ed&n", "67", "eventim");

    assertEquals(salleProche, resultat);
  }

  // En centre-ville, plusieurs salles distinctes tiennent dans le rayon autorisé : la plus
  // proche doit l'emporter, pas la première renvoyée par la requête.
  @Test
  void chercher_plusieursCandidatsDansLeRayon_retourneLePlusProche() {
    final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    final ResolveurSalleParProximiteGps resolveur = new ResolveurSalleParProximiteGps(jdbc);
    final UUID eloigne = UUID.randomUUID();
    final UUID proche = UUID.randomUUID();
    final Map<String, Object> candidatEloigne = new HashMap<>();
    candidatEloigne.put("id", eloigne);
    candidatEloigne.put("nom_norm", "salle alpha");
    candidatEloigne.put("latitude", 48.8600);
    candidatEloigne.put("longitude", 2.3500);
    final Map<String, Object> candidatProche = new HashMap<>();
    candidatProche.put("id", proche);
    candidatProche.put("nom_norm", "salle beta");
    candidatProche.put("latitude", 48.8567);
    candidatProche.put("longitude", 2.3523);
    when(jdbc.queryForList(anyString(), any(SqlParameterSource.class)))
        .thenReturn(List.of(candidatEloigne, candidatProche));

    final UUID resultat =
        resolveur.chercher(
            new ClientNominatim.Coordonnees(48.8566, 2.3522), "Zenith Inconnu", "75", "eventim");

    assertEquals(proche, resultat);
  }
}
