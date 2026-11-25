#!/usr/bin/env python3
"""Vérifie un lot de rotation (section 9) contre des sources indépendantes.

Artistes : libellé Wikidata (fr, en ou mul, lu sur WDQS v2 en une requête par lot) et nom Deezer comparés au nom suivi ;
un identifiant Deezer à moins de 2 000 fans est signalé (cas Sellig : homonyme
exact peu suivi). Salles : libellé Wikidata et écart entre la position stockée
et le P625 (signalé au-delà de 500 m, sans position Wikidata comparable = A VOIR).

Usage :
    python3 verifier_rotation.py artistes
    python3 verifier_rotation.py salles --disjoint

Par défaut, lit le dernier snapshot de run_rotation.py ; --disjoint prend le lot
suivant (contre-vérification du subagent, curseur partagé intact). Lecture seule.
"""

import argparse
import json
import subprocess
from pathlib import Path
import sys
import math
import re
import time
import unicodedata
import urllib.request

sys.path.insert(0, str(Path(__file__).resolve().parent))  # lancement possible avec python3 -I
import lot_rotation as lot

AGENT = "strapontin-suivi/1.0"  # identique à celui des tests WDQS v2 du 25/11/2026
SEUIL_FANS = 2000
SEUIL_POSITION_METRES = 500


def normaliser(texte: str) -> str:
    texte = (
        unicodedata.normalize("NFKD", (texte or "").replace("&", " and "))
        .encode("ascii", "ignore")
        .decode()
        .lower()
    )
    return re.sub(r"[^a-z0-9]+", " ", texte).strip()


def charger_json(url: str):
    # curl et non urllib : Wikimedia répond 429 à l'empreinte d'urllib (constaté le 25/11/2026)
    for tentative in range(3):
        sortie = subprocess.run(
            ["curl", "-s", "--max-time", "15", "-A", AGENT, url],
            capture_output=True, text=True,
        )
        try:
            return json.loads(sortie.stdout)
        except ValueError:
            time.sleep(2 * (tentative + 1))
    return None


POINT_WKT = re.compile(r"POINT\(\s*(-?[0-9.eE+-]+)\s+(-?[0-9.eE+-]+)\s*\)", re.IGNORECASE)
URL_WDQS_V2 = "https://query-next.wikidata.org/sparql"


def entites_wikidata(identifiants: list[str]) -> dict[str, dict]:
    """Libellé (fr, en, mul) et P625 de chaque entité, en une requête WDQS v2.

    v2 (QLever) n'a pas de budget de calcul par client, contrairement à v1 ;
    les coordonnées reviennent en POINT(lon lat).
    """
    valeurs = " ".join(f"wd:{i}" for i in sorted(set(identifiants)) if re.fullmatch(r"Q[0-9]+", i))
    if not valeurs:
        return {}
    # Trois OPTIONAL séparés : la forme BIND(LANG()) + IN dépasse 60 s sur QLever (testé le 25/11/2026)
    requete = (
        "SELECT ?item ?lfr ?len ?lmul ?coord WHERE { VALUES ?item { " + valeurs + " } "
        'OPTIONAL { ?item rdfs:label ?lfr . FILTER(LANG(?lfr)="fr") } '
        'OPTIONAL { ?item rdfs:label ?len . FILTER(LANG(?len)="en") } '
        'OPTIONAL { ?item rdfs:label ?lmul . FILTER(LANG(?lmul)="mul") } '
        "OPTIONAL { ?item wdt:P625 ?coord } }"
    )
    resultat: dict[str, dict] = {}
    for tentative in range(3):
        sortie = subprocess.run(
            ["curl", "-s", "--max-time", "40", "-A", AGENT, "-G", URL_WDQS_V2,
             "--data-urlencode", f"query={requete}",
             "-H", "Accept: application/sparql-results+json"],
            capture_output=True, text=True,
        )
        try:
            lignes = json.loads(sortie.stdout)["results"]["bindings"]
            break
        except (ValueError, KeyError):
            time.sleep(3 * (tentative + 1))
    else:
        return {}
    for ligne in lignes:
        identifiant = ligne["item"]["value"].rsplit("/", 1)[-1]
        entite = resultat.setdefault(identifiant, {"labels": {}, "coord": None})
        for langue, cle in (("fr", "lfr"), ("en", "len"), ("mul", "lmul")):
            if cle in ligne:
                entite["labels"][langue] = ligne[cle]["value"]
        if "coord" in ligne and entite["coord"] is None:
            m = POINT_WKT.search(ligne["coord"]["value"])
            if m:
                entite["coord"] = (float(m.group(2)), float(m.group(1)))
    return resultat


def libelle(entite) -> str | None:
    if not entite:
        return None
    for langue in ("fr", "en", "mul"):
        if entite["labels"].get(langue):
            return entite["labels"][langue]
    return None


def distance_metres(lat1: float, lon1: float, lat2: float, lon2: float) -> float:
    p = math.pi / 180
    a = (
        math.sin((lat2 - lat1) * p / 2) ** 2
        + math.cos(lat1 * p) * math.cos(lat2 * p) * math.sin((lon2 - lon1) * p / 2) ** 2
    )
    return 12742000 * math.asin(math.sqrt(a))


def verifier_artistes(lignes: list[list[str]]) -> None:
    entites = entites_wikidata([c[2] for c in lignes if len(c) >= 4 and c[2]])
    for cellules in lignes:
        if len(cellules) < 4:
            continue
        _, nom, wikidata, deezer = cellules[:4]
        nom_wikidata = libelle(entites.get(wikidata)) if wikidata else None
        artiste = (
            charger_json(f"https://api.deezer.com/artist/{deezer}") if deezer else None
        )
        nom_deezer = artiste.get("name") if artiste else None
        fans = artiste.get("nb_fan") if artiste else None
        motifs = []
        if nom_wikidata is None:
            motifs.append("libellé Wikidata absent")
        elif normaliser(nom_wikidata) != normaliser(nom):
            motifs.append("libellé Wikidata différent")
        if deezer and nom_deezer is None:
            motifs.append("Deezer introuvable")
        elif nom_deezer is not None and normaliser(nom_deezer) != normaliser(nom):
            motifs.append("nom Deezer différent")
        if fans is not None and fans < SEUIL_FANS:
            motifs.append(f"seulement {fans} fans")
        etat = "OK " if not motifs else "A VOIR "
        print(
            f"{etat}{nom} | wd {wikidata}={nom_wikidata} | dz {deezer}={nom_deezer} ({fans}) {'; '.join(motifs)}",
            flush=True,
        )
        time.sleep(0.3)


def positions_salles(identifiants: list[str]) -> dict[str, tuple[float, float]]:
    liste = ",".join(f"'{identifiant}'" for identifiant in identifiants)
    sortie = lot.executer_sql(
        f"select id, latitude, longitude from salles where id in ({liste}) and latitude is not null;\n"
    )
    return {
        c[0]: (float(c[1]), float(c[2])) for c in lot.lignes_psql(sortie) if len(c) == 3
    }


def verifier_salles(lignes: list[list[str]]) -> None:
    positions = positions_salles([c[0] for c in lignes if len(c) >= 5])
    entites = entites_wikidata([c[4] for c in lignes if len(c) >= 5 and c[4]])
    for cellules in lignes:
        if len(cellules) < 5:
            continue
        identifiant, nom, ville, dept, wikidata = cellules[:5]
        entite = entites.get(wikidata) if wikidata else None
        nom_wikidata = libelle(entite)
        ecart = None
        coordonnees = (entite or {}).get("coord")
        if coordonnees and identifiant in positions:
            ecart = distance_metres(*positions[identifiant], *coordonnees)
        motifs = []
        if nom_wikidata is None:
            motifs.append("libellé Wikidata absent")
        if ecart is None:
            motifs.append("position non comparable")
        elif ecart > SEUIL_POSITION_METRES:
            motifs.append(f"écart de position {round(ecart)} m")
        etat = "OK " if not motifs else "A VOIR "
        texte_ecart = "?" if ecart is None else round(ecart)
        print(
            f"{etat}{nom} | {ville} {dept} | wd {wikidata}={nom_wikidata} | écart {texte_ecart} m {'; '.join(motifs)}",
            flush=True,
        )


def main() -> None:
    parseur = argparse.ArgumentParser(description=(__doc__ or "").splitlines()[0])
    parseur.add_argument("entite", choices=["artistes", "salles"])
    parseur.add_argument(
        "--disjoint",
        action="store_true",
        help="vérifier le lot suivant celui du snapshot",
    )
    arguments = parseur.parse_args()
    lignes = (
        lot.lot_disjoint(arguments.entite)
        if arguments.disjoint
        else lot.lot_snapshot(arguments.entite)
    )
    print(
        f"== {arguments.entite} ({len(lignes)} lignes, {'lot disjoint' if arguments.disjoint else 'snapshot'})",
        flush=True,
    )
    if arguments.entite == "artistes":
        verifier_artistes(lignes)
    else:
        verifier_salles(lignes)


if __name__ == "__main__":
    main()
