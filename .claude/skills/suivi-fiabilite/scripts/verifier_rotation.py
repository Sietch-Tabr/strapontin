#!/usr/bin/env python3
"""Vérifie un lot de rotation (section 9) contre des sources indépendantes.

Artistes : libellé Wikidata (fr, en ou mul) et nom Deezer comparés au nom suivi ;
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
from pathlib import Path
import sys
import math
import re
import time
import unicodedata
import urllib.request

sys.path.insert(0, str(Path(__file__).resolve().parent))  # lancement possible avec python3 -I
import lot_rotation as lot

AGENT = "strapontin-suivi/1.0"
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
    requete = urllib.request.Request(url, headers={"User-Agent": AGENT})
    for tentative in range(3):
        try:
            with urllib.request.urlopen(requete, timeout=15) as reponse:
                return json.load(reponse)
        except Exception:
            time.sleep(2 * (tentative + 1))
    return None


def entite_wikidata(identifiant: str):
    donnees = charger_json(
        f"https://www.wikidata.org/wiki/Special:EntityData/{identifiant}.json"
    )
    time.sleep(1)
    if not donnees:
        return None
    return donnees["entities"].get(identifiant) or next(
        iter(donnees["entities"].values())
    )


def libelle(entite) -> str | None:
    if not entite:
        return None
    for langue in ("fr", "en", "mul"):
        valeur = entite.get("labels", {}).get(langue, {}).get("value")
        if valeur:
            return valeur
    return None


def distance_metres(lat1: float, lon1: float, lat2: float, lon2: float) -> float:
    p = math.pi / 180
    a = (
        math.sin((lat2 - lat1) * p / 2) ** 2
        + math.cos(lat1 * p) * math.cos(lat2 * p) * math.sin((lon2 - lon1) * p / 2) ** 2
    )
    return 12742000 * math.asin(math.sqrt(a))


def verifier_artistes(lignes: list[list[str]]) -> None:
    for cellules in lignes:
        if len(cellules) < 4:
            continue
        _, nom, wikidata, deezer = cellules[:4]
        nom_wikidata = libelle(entite_wikidata(wikidata)) if wikidata else None
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
    for cellules in lignes:
        if len(cellules) < 5:
            continue
        identifiant, nom, ville, dept, wikidata = cellules[:5]
        entite = entite_wikidata(wikidata) if wikidata else None
        nom_wikidata = libelle(entite)
        ecart = None
        revendications = (entite or {}).get("claims", {}).get("P625")
        if revendications and identifiant in positions:
            valeur = revendications[0]["mainsnak"]["datavalue"]["value"]
            ecart = distance_metres(
                *positions[identifiant], valeur["latitude"], valeur["longitude"]
            )
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
