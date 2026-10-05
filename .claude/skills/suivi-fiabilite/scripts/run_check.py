#!/usr/bin/env python3
"""Exécute un fichier SQL versionné de checks/ contre la prod et diff contre le
dernier snapshot du même check (pattern dbt/Great Expectations : vérifications
figées et versionnées, jamais retapées de mémoire — voir HISTORIQUE.md, écart
"géo-incohérence 1 puis 8" du 06/08/2026 causé par une requête réécrite
légèrement différemment entre deux checks).

Usage :
    python3 run_check.py geo_incoherence_salles
    python3 run_check.py --all

Chaque exécution est sauvegardée horodatée dans snapshots/<check>/<ISO8601>.txt
et diffée contre le snapshot précédent du même check (aucun diff affiché au
tout premier passage, rien à comparer).
"""

import argparse
import difflib
import subprocess
import sys
from datetime import datetime, timezone
from pathlib import Path

SCRIPT_DIR = Path(__file__).resolve().parent
CHECKS_DIR = SCRIPT_DIR / "checks"
SNAPSHOTS_DIR = SCRIPT_DIR.parent / "snapshots"
# Accès à la base de prod : fichier local non versionné (gabarit : .claude/scripts/acces.exemple.sh).
ACCES = SCRIPT_DIR.parents[2] / "scripts" / "acces.local.sh"


def run(cmd: list[str], **kw) -> subprocess.CompletedProcess:
    return subprocess.run(cmd, capture_output=True, text=True, timeout=60, **kw)


def executer_check(nom: str) -> str:
    sql_local = CHECKS_DIR / f"{nom}.sql"
    if not sql_local.is_file():
        raise SystemExit(f"check inconnu : {sql_local} introuvable")

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
    if diff:
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
    args = parser.parse_args()

    if args.all:
        noms = sorted(p.stem for p in CHECKS_DIR.glob("*.sql"))
    elif args.check:
        noms = [args.check]
    else:
        parser.print_usage()
        return 1

    for nom in noms:
        try:
            sortie = executer_check(nom)
        except RuntimeError as e:
            print(f"erreur : {e}", file=sys.stderr)
            continue
        snapshot_et_diff(nom, sortie)
    return 0


if __name__ == "__main__":
    sys.exit(main())
