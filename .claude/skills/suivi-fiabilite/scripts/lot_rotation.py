"""Lecture des lots de rotation (section 9) pour les scripts de vérification.

Deux sources de lignes, sans jamais écrire dans curseurs_rotation.json ni
dans snapshots/ :
- le dernier snapshot écrit par run_rotation.py (lot de l'agent principal) ;
- le lot disjoint : le lot suivant celui du snapshot, obtenu en exécutant la
  requête figée de rotation/<entité>.sql avec le dernier id du snapshot comme
  curseur (contre-vérification du subagent, voir SKILL.md section 11).
"""

import subprocess
import tempfile
from pathlib import Path

SCRIPT_DIR = Path(__file__).resolve().parent
ROTATION_DIR = SCRIPT_DIR / "rotation"
SNAPSHOTS_DIR = SCRIPT_DIR.parent / "snapshots"
# Colonne portant la valeur filtrée par le curseur (0 = première colonne) : user_matches filtre sur concert_id.
COLONNE_CURSEUR = {"user_matches": 1}
# Accès à la base de prod : fichier local non versionné (gabarit : .claude/scripts/acces.exemple.sh).
ACCES = SCRIPT_DIR.parents[2] / "scripts" / "acces.local.sh"


def lignes_psql(sortie: str) -> list[list[str]]:
    """Découpe une sortie psql alignée en lignes de cellules, en-têtes exclus."""
    lignes = sortie.splitlines()
    lignes_donnees = []
    for position, ligne in enumerate(lignes):
        texte = ligne.strip()
        if "|" not in ligne or texte.startswith("(") or set(texte) <= set("-+"):
            continue
        suivante = lignes[position + 1].strip() if position + 1 < len(lignes) else ""
        if suivante and set(suivante) <= set("-+"):
            continue  # ligne d'en-tête, suivie du filet de séparation
        lignes_donnees.append([cellule.strip() for cellule in ligne.split("|")])
    return lignes_donnees


def dernier_snapshot(entite: str) -> Path:
    fichiers = sorted((SNAPSHOTS_DIR / f"rotation_{entite}").glob("*.txt"))
    if not fichiers:
        raise SystemExit(f"aucun snapshot de rotation pour {entite}")
    return fichiers[-1]


def lot_snapshot(entite: str) -> list[list[str]]:
    return lignes_psql(dernier_snapshot(entite).read_text())


def executer_sql(requete: str) -> str:
    """Exécute une requête en lecture seule sur la prod via acces.local.sh."""
    with tempfile.TemporaryDirectory() as dossier:
        fichier = Path(dossier) / "requete.sql"
        fichier.write_text(requete)
        resultat = subprocess.run(
            [str(ACCES), "prod", "psql", str(fichier)],
            capture_output=True,
            text=True,
            timeout=90,
        )
    return resultat.stdout


def lot_disjoint(entite: str) -> list[list[str]]:
    """Lot suivant celui du dernier snapshot, sans avancer le curseur partagé."""
    lot_actuel = lot_snapshot(entite)
    if not lot_actuel:
        raise SystemExit(f"snapshot vide pour {entite}")
    dernier_id = lot_actuel[-1][COLONNE_CURSEUR.get(entite, 0)]
    gabarit = (ROTATION_DIR / f"{entite}.sql").read_text()
    requete = gabarit.replace("__CURSEUR__", dernier_id).replace(
        "__LOT__", str(len(lot_actuel))
    )
    return lignes_psql(executer_sql(requete))
