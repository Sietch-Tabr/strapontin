#!/usr/bin/env python3
"""Volet TM de la section 8 : compare le nom des artistes d'un lot de rotation au
nom de l'attraction Ticketmaster (GET /attractions/{id}.json).

Usage :
    python3 decrypt_config.py TICKETMASTER_API_KEY "$TMPDIR/cle_tm.txt"   # accord explicite requis
    python3 comparer_tm_lot.py "$TMPDIR/cle_tm.txt"                        # lot du snapshot
    python3 comparer_tm_lot.py "$TMPDIR/cle_tm.txt" --disjoint             # lot disjoint (subagent)

La clé n'est jamais affichée et le fichier de clé est supprimé à la fin, y compris
en cas d'erreur. Cadence : 300 ms entre deux appels.
"""

import argparse
import json
import sys
import re
import time
import unicodedata
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))  # lancement possible avec python3 -I
import lot_rotation as lot

DELAI_SECONDES = 0.3


def normaliser(texte: str) -> str:
    texte = unicodedata.normalize("NFD", (texte or "").lower().replace("&", " and "))
    texte = "".join(c for c in texte if unicodedata.category(c) != "Mn")
    return re.sub(r"[^a-z0-9]+", " ", texte).strip()


def identifiants_tm(noms: list[str]) -> list[tuple[str, str]]:
    liste = ",".join("'" + nom.replace("'", "''") + "'" for nom in noms)
    sortie = lot.executer_sql(
        "select a.nom, s.identifiant_externe from artistes a join artiste_sources s on s.artiste_id = a.id "
        f"where s.source = 'ticketmaster' and a.nom in ({liste}) order by 1;\n"
    )
    return [(c[0], c[1]) for c in lot.lignes_psql(sortie) if len(c) == 2 and c[1]]


def nom_attraction(identifiant: str, cle: str) -> str:
    adresse = (
        f"https://app.ticketmaster.com/discovery/v2/attractions/{identifiant}.json"
        f"?apikey={urllib.parse.quote(cle)}&locale=*"
    )
    for _ in range(3):
        try:
            with urllib.request.urlopen(adresse, timeout=30) as reponse:
                return json.load(reponse).get("name") or ""
        except urllib.error.HTTPError as erreur:
            if erreur.code == 429:
                time.sleep(3)
                continue
            return f"HTTP {erreur.code}"
        except Exception as erreur:
            return "erreur " + str(erreur)[:40]
    return "HTTP 429"


def main() -> None:
    parseur = argparse.ArgumentParser(description=(__doc__ or "").splitlines()[0])
    parseur.add_argument("fichier_cle", type=Path)
    parseur.add_argument(
        "--disjoint",
        action="store_true",
        help="comparer le lot suivant celui du snapshot",
    )
    arguments = parseur.parse_args()
    try:
        cle = arguments.fichier_cle.read_text().strip()
        lignes = (
            lot.lot_disjoint("artistes")
            if arguments.disjoint
            else lot.lot_snapshot("artistes")
        )
        noms = [c[1] for c in lignes if len(c) >= 2]
        paires = identifiants_tm(noms)
        print(f"{len(paires)} artistes avec identifiant TM sur {len(noms)}")
        for nom, identifiant in paires:
            nom_tm = nom_attraction(identifiant, cle)
            etat = "OK " if normaliser(nom_tm) == normaliser(nom) else "A VOIR "
            print(f"{etat}{nom} | {identifiant} | TM={nom_tm}")
            time.sleep(DELAI_SECONDES)
    finally:
        arguments.fichier_cle.unlink(missing_ok=True)


if __name__ == "__main__":
    main()
