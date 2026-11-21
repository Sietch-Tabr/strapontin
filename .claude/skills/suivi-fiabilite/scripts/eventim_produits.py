#!/usr/bin/env python3
"""Interroge l'API Eventim depuis le LXC prod (l'API refuse tout User-Agent de type
navigateur : seul curl passe) et dit si des produits existent encore.

Usage :
    python3 eventim_produits.py "brooke combe"
    python3 eventim_produits.py "nieve ella" --ids 21964618 22275856
    python3 eventim_produits.py "houle" --du 2026-11-01 --au 2027-12-31

Affiche pour chaque produit renvoyé : identifiant, nom, statut, date, lieu, et pour chaque
--ids demandé « TROUVE » ou « absent » (produit retiré, republié sous un autre identifiant
ou dans une autre salle). Lecture seule.
"""

import argparse
import base64
import json
import subprocess
import sys
from pathlib import Path

ACCES = Path(__file__).resolve().parents[3] / "scripts" / "acces.local.sh"

# Exécuté sur le LXC prod : curl par défaut, jamais urllib ni un UA personnalisé.
SCRIPT_DISTANT = r"""
import json, subprocess, sys, urllib.parse
p = json.loads(sys.argv[1])
q = urllib.parse.urlencode({"webId": "web__eventim-fr", "language": "fr", "search_term": p["terme"],
                            "date_from": p["du"], "date_to": p["au"], "sort": "DateAsc", "top": 50})
sortie = subprocess.run(["curl", "-s", "--max-time", "30",
    "https://public-api.eventim.com/websearch/search/api/exploration/v1/products?" + q],
    capture_output=True, text=True).stdout
try:
    r = json.loads(sortie)
except Exception:
    print(json.dumps({"erreur": sortie[:120]})); sys.exit(0)
produits = []
for x in r.get("products", []):
    lieux = [v.get("name") for v in x.get("venues", []) if v.get("name")]
    produits.append({"id": str(x.get("productId")), "nom": x.get("name"), "statut": x.get("status"),
                     "debut": x.get("startDate"), "lieux": lieux, "lien": str(x.get("link"))})
print(json.dumps({"total": r.get("totalResults"), "produits": produits}))
"""


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("terme", help="terme de recherche (artiste ou spectacle)")
    parser.add_argument("--ids", nargs="*", default=[], help="identifiants de produits à retrouver")
    parser.add_argument("--du", default="2026-01-01", help="date_from (AAAA-MM-JJ)")
    parser.add_argument("--au", default="2028-12-31", help="date_to (AAAA-MM-JJ)")
    args = parser.parse_args()

    if not ACCES.is_file():
        raise SystemExit(f"accès non configuré : {ACCES} introuvable")
    parametres = json.dumps({"terme": args.terme, "du": args.du, "au": args.au})
    code = base64.b64encode(SCRIPT_DISTANT.encode()).decode()
    # Paramètres en base64 : une apostrophe dans le terme casserait les guillemets du shell distant.
    parametres_b64 = base64.b64encode(parametres.encode()).decode()
    commande = (
        f"echo {code} | base64 -d > /tmp/eventim_produits.py && "
        f"python3 /tmp/eventim_produits.py \"$(echo {parametres_b64} | base64 -d)\"; "
        "rm -f /tmp/eventim_produits.py"
    )
    resultat = subprocess.run(
        [str(ACCES), "prod", "exec", commande], capture_output=True, text=True, timeout=90
    )
    try:
        donnees = json.loads(resultat.stdout.strip().splitlines()[-1])
    except (IndexError, json.JSONDecodeError):
        print("réponse illisible :", resultat.stdout[:200], resultat.stderr[:200])
        return 1
    if "erreur" in donnees:
        print("API Eventim :", donnees["erreur"])
        return 1
    print(f"{donnees['total']} résultat(s) pour « {args.terme} »")
    for p in donnees["produits"]:
        print(f"{p['id']} | {p['nom']} | {p['statut']} | {p['debut']} | {', '.join(p['lieux'])} | ...{p['lien'][-70:]}")
    renvoyes = {p["id"] for p in donnees["produits"]}
    for identifiant in args.ids:
        print(f"produit {identifiant} : {'TROUVE' if identifiant in renvoyes else 'absent'}")
    if any(i not in renvoyes for i in args.ids):
        print("Attention : 50 produits au plus, triés par date. Un « absent » ne prouve rien tant que "
              "--du/--au ne sont pas resserrés sur la date du concert.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
