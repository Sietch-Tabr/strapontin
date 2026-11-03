#!/usr/bin/env python3
"""Exécute un fichier SQL versionné de checks/ contre la prod et diff contre le
dernier snapshot du même check (pattern dbt/Great Expectations : vérifications
figées et versionnées, jamais retapées de mémoire — voir HISTORIQUE.md, écart
"géo-incohérence 1 puis 8" du 06/08/2026 causé par une requête réécrite
légèrement différemment entre deux checks).

Usage :
    python3 run_check.py geo_incoherence_salles
    python3 run_check.py --all
    python3 run_check.py bilan_fenetre --borne "2026-11-02 21:34"

Un check dont le SQL contient __BORNE__ (heure de Paris du dernier check) exige --borne
et n'est pas lancé par --all sans elle. Un diff de plus de 60 lignes est résumé
(comptes et premières lignes) ; le snapshot complet reste dans snapshots/<check>/.

Chaque exécution est sauvegardée horodatée dans snapshots/<check>/<ISO8601>.txt
et diffée contre le snapshot précédent du même check (aucun diff affiché au
tout premier passage, rien à comparer).
"""

import argparse
import difflib
import os
import subprocess
import sys
import tempfile
from datetime import datetime, timezone
from pathlib import Path

SCRIPT_DIR = Path(__file__).resolve().parent
CHECKS_DIR = SCRIPT_DIR / "checks"
SNAPSHOTS_DIR = SCRIPT_DIR.parent / "snapshots"
# Accès à la base de prod : fichier local non versionné (gabarit : .claude/scripts/acces.exemple.sh).
ACCES = SCRIPT_DIR.parents[2] / "scripts" / "acces.local.sh"


def run(cmd: list[str], **kw) -> subprocess.CompletedProcess:
    return subprocess.run(cmd, capture_output=True, text=True, timeout=120, **kw)


SEUIL_DIFF_COMPACT = 60
LIGNES_DIFF_AFFICHEES = 15


def executer_check(nom: str, borne: str | None = None) -> str:
    sql_local = CHECKS_DIR / f"{nom}.sql"
    if not sql_local.is_file():
        raise SystemExit(f"check inconnu : {sql_local} introuvable")
    texte = sql_local.read_text(encoding="utf-8")
    if "__BORNE__" in texte:
        if not borne:
            raise RuntimeError(f"{nom} exige --borne \"AAAA-MM-JJ HH:MM\" (heure de Paris)")
        # acces.local.sh copie le fichier sous /tmp/acces_<nom>.sql : nom unique par exécution.
        with tempfile.TemporaryDirectory() as dossier:
            sql_local = Path(dossier) / f"{nom}_{os.getpid()}.sql"
            sql_local.write_text(texte.replace("__BORNE__", borne), encoding="utf-8")
            return _lancer(sql_local, nom)
    return _lancer(sql_local, nom)


def _lancer(sql_local: Path, nom: str) -> str:
    if not ACCES.is_file():
        raise SystemExit(f"accès non configuré : {ACCES} introuvable (voir acces.exemple.sh)")
    push_et_exec = run([str(ACCES), "prod", "psql", str(sql_local)])
    if push_et_exec.returncode != 0:
        raise RuntimeError(
            f"exécution échouée pour {nom} : {push_et_exec.stderr.strip()}"
        )
    return push_et_exec.stdout


def snapshot_et_diff(nom: str, sortie: str) -> None:
    dossier = SNAPSHOTS_DIR / nom
    dossier.mkdir(parents=True, exist_ok=True)

    precedents = sorted(dossier.glob("*.txt"))
    horodatage = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    nouveau_fichier = dossier / f"{horodatage}.txt"
    nouveau_fichier.write_text(sortie)

    print(f"\n=== {nom} ===")
    if not precedents:
        print(
            f"(premier snapshot, rien à comparer — sauvegardé dans {nouveau_fichier})"
        )
        print(sortie)
        return

    ancien_fichier = precedents[-1]
    ancien = ancien_fichier.read_text()
    diff = list(
        difflib.unified_diff(
            ancien.splitlines(keepends=True),
            sortie.splitlines(keepends=True),
            fromfile=f"précédent ({ancien_fichier.name})",
            tofile=f"actuel ({nouveau_fichier.name})",
        )
    )
    if len(diff) > SEUIL_DIFF_COMPACT:
        ajouts = sum(1 for l in diff if l.startswith("+") and not l.startswith("+++"))
        retraits = sum(1 for l in diff if l.startswith("-") and not l.startswith("---"))
        print(f"(diff volumineux : {ajouts} lignes ajoutées, {retraits} retirées ; {LIGNES_DIFF_AFFICHEES} premières lignes)")
        print("".join(diff[: LIGNES_DIFF_AFFICHEES + 2]))
        print(f"(complet : diff {ancien_fichier} {nouveau_fichier})")
    elif diff:
        print("".join(diff))
    else:
        print(f"(identique au précédent snapshot {ancien_fichier.name} — 0 delta)")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "check", nargs="?", help="nom du check (sans .sql), ex. geo_incoherence_salles"
    )
    parser.add_argument(
        "--all", action="store_true", help="exécute tous les checks de checks/"
    )
    parser.add_argument(
        "--borne",
        help='borne basse (heure de Paris) des checks à fenêtre, ex. "2026-11-02 21:34"',
    )
    args = parser.parse_args()

    if args.all:
        noms = sorted(
            p.stem
            for p in CHECKS_DIR.glob("*.sql")
            if args.borne or "__BORNE__" not in p.read_text(encoding="utf-8")
        )
    elif args.check:
        noms = [args.check]
    else:
        parser.print_usage()
        return 1

    for nom in noms:
        try:
            sortie = executer_check(nom, args.borne)
        except RuntimeError as e:
            print(f"erreur : {e}", file=sys.stderr)
            continue
        snapshot_et_diff(nom, sortie)
    return 0


if __name__ == "__main__":
    sys.exit(main())
