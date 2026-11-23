#!/usr/bin/env python3
"""Exécute un lot de la rotation section 9 (couverture exhaustive par curseur)
contre la prod, sauvegarde un snapshot horodaté (même pattern que
run_check.py/checks/ pour les sections 5/7) et avance le curseur persisté dans
curseurs_rotation.json.

Contrairement aux checks de sections 5/7 (requête figée, résultat comparable
d'un check à l'autre), une rotation change de contenu à chaque exécution par
construction (le curseur avance) — le snapshot ici sert d'audit trail (quelles
lignes ont réellement été tirées à quelle date), pas de diff contre le
précédent. Ajouté le 24/08/2026 : jusque-là la section 9 n'avait aucune trace
persistée de ses résultats bruts, rendant tout désaccord sur son contenu
impossible à trancher après coup (voir HISTORIQUE.md).

Usage :
    python3 run_rotation.py artistes
    python3 run_rotation.py --all

Si le lot renvoie moins de lignes que sa taille (fin de table atteinte), le
script boucle automatiquement depuis '' pour compléter le lot dans la même
exécution (comportement prescrit par SKILL.md section 9), puis marque
tour_complet_le à la date du jour.
"""

import argparse
import json
import subprocess
import sys
from datetime import date
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))  # lancement possible avec python3 -I
from lot_rotation import COLONNE_CURSEUR  # noqa: E402

SCRIPT_DIR = Path(__file__).resolve().parent
ROTATION_DIR = SCRIPT_DIR / "rotation"
SNAPSHOTS_DIR = SCRIPT_DIR.parent / "snapshots"
CURSEURS_FILE = SCRIPT_DIR / "curseurs_rotation.json"
# Accès à la base de prod : fichier local non versionné (gabarit : .claude/scripts/acces.exemple.sh).
ACCES = SCRIPT_DIR.parents[2] / "scripts" / "acces.local.sh"


def run(cmd: list[str], **kw) -> subprocess.CompletedProcess:
    return subprocess.run(cmd, capture_output=True, text=True, timeout=60, **kw)


def charger_curseurs() -> dict:
    if not CURSEURS_FILE.is_file():
        raise SystemExit(f"curseurs introuvable : {CURSEURS_FILE}")
    return json.loads(CURSEURS_FILE.read_text())


def sauvegarder_curseurs(curseurs: dict) -> None:
    CURSEURS_FILE.write_text(json.dumps(curseurs, indent=2, ensure_ascii=False) + "\n")


def executer_lot(nom: str, curseur: str, lot: int) -> str:
    sql_local = ROTATION_DIR / f"{nom}.sql"
    if not sql_local.is_file():
        raise SystemExit(f"rotation inconnue : {sql_local} introuvable")

    gabarit = sql_local.read_text()
    requete = gabarit.replace("__CURSEUR__", curseur).replace("__LOT__", str(lot))

    fichier_tmp = Path(f"/tmp/rotation_{nom}_local.sql")
    fichier_tmp.write_text(requete)
    if not ACCES.is_file():
        raise SystemExit(f"accès non configuré : {ACCES} introuvable (voir acces.exemple.sh)")
    push_et_exec = run([str(ACCES), "prod", "psql", str(fichier_tmp)])
    if push_et_exec.returncode != 0:
        raise RuntimeError(
            f"exécution échouée pour {nom} : {push_et_exec.stderr.strip()}"
        )
    return push_et_exec.stdout


def compter_lignes_donnees(sortie_psql: str) -> int:
    """Compte les lignes de données d'une sortie psql (exclut en-tête, séparateur, pied de page)."""
    lignes = sortie_psql.strip().splitlines()
    nb = 0
    for ligne in lignes:
        if ligne.strip().endswith("lignes)") or ligne.strip().endswith("ligne)"):
            continue
        if ligne.strip().startswith("---") or ligne.strip().startswith("("):
            continue
        nb += 1
    # la ligne séparatrice est déjà exclue ci-dessus (startswith "---") ; seule
    # la ligne d'en-tête (noms de colonnes) reste à soustraire
    return max(nb - 1, 0)


def dernier_id(sortie_psql: str, colonne: int = 0) -> str | None:
    """Extrait la valeur de la colonne du curseur (première par défaut) de la dernière ligne de données."""
    lignes = [l for l in sortie_psql.strip().splitlines() if l.strip()]
    lignes_donnees = [
        l
        for l in lignes
        if not l.strip().startswith("---")
        and not l.strip().startswith("(")
        and "|" in l
    ]
    if len(lignes_donnees) < 2:  # en-tête + au moins 1 ligne
        return None
    derniere = lignes_donnees[-1]
    return derniere.split("|")[colonne].strip()


def executer_rotation(nom: str, curseurs: dict) -> None:
    etat = curseurs[nom]
    curseur, lot = etat["curseur"], etat["lot"]

    sortie = executer_lot(nom, curseur, lot)
    nb_lignes = compter_lignes_donnees(sortie)
    # fin de table atteinte dès lors que la page renvoie moins que le lot demandé —
    # que ce soit parce que la table entière tient dans la 1re page (curseur déjà
    # à '') ou parce que le curseur en cours arrive en fin de table en cours de
    # route (dans ce dernier cas, reboucler depuis '' dans la même exécution pour
    # compléter le lot, comme prescrit par le skill)
    tour_complet = nb_lignes < lot

    if tour_complet and curseur != "":
        sortie_suite = executer_lot(nom, "", lot - nb_lignes)
        sortie += (
            "\n-- (tour complet atteint, repris depuis '' pour compléter le lot) --\n\n"
            + sortie_suite
        )

    dossier = SNAPSHOTS_DIR / f"rotation_{nom}"
    dossier.mkdir(parents=True, exist_ok=True)
    from datetime import datetime, timezone

    horodatage = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    fichier = dossier / f"{horodatage}.txt"
    fichier.write_text(sortie)

    print(f"\n=== rotation: {nom} ===")
    print(sortie)

    if tour_complet:
        # "repartir du début au check suivant" (SKILL.md section 9) : le
        # prochain lancement redémarre la table entière, pas seulement la
        # queue du tour qui vient de se terminer
        etat["curseur"] = ""
        etat["tour_complet_le"] = date.today().isoformat()
        print(f"(tour complet — snapshot : {fichier})")
    else:
        nouveau_curseur = dernier_id(sortie, COLONNE_CURSEUR.get(nom, 0))
        if nouveau_curseur:
            etat["curseur"] = nouveau_curseur
        print(f"(snapshot : {fichier})")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("rotation", nargs="?", help="nom de la rotation, ex. salles")
    parser.add_argument(
        "--all", action="store_true", help="exécute toutes les rotations"
    )
    args = parser.parse_args()

    curseurs = charger_curseurs()

    if args.all:
        noms = sorted(curseurs.keys())
    elif args.rotation:
        if args.rotation not in curseurs:
            raise SystemExit(
                f"rotation inconnue : {args.rotation} (attendu : {sorted(curseurs.keys())})"
            )
        noms = [args.rotation]
    else:
        parser.print_usage()
        return 1

    for nom in noms:
        try:
            executer_rotation(nom, curseurs)
        except RuntimeError as e:
            print(f"erreur : {e}", file=sys.stderr)
            continue

    sauvegarder_curseurs(curseurs)
    return 0


if __name__ == "__main__":
    sys.exit(main())
