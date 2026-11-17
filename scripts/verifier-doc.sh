#!/bin/bash
# Compare la documentation (wiki, CLAUDE.md, changelog) au code : à lancer avant une PR de jalon
# (/pre-pr). Le wiki est un dépôt séparé, le contrôle ne peut donc pas tourner dans la pipeline.
#
# Usage : scripts/verifier-doc.sh [dépôt-du-wiki]   (défaut : ../strapontin-wiki, ou $DEPOT_WIKI)
# Sortie : une ligne [OK] ou [ERREUR] par contrôle ; code de sortie 1 au premier écart.

set -euo pipefail

racine="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
wiki="${1:-${DEPOT_WIKI:-$racine/../strapontin-wiki}}"

if [ ! -d "$wiki" ]; then
  echo "[ERREUR] dépôt du wiki introuvable : $wiki" >&2
  exit 2
fi

RACINE="$racine" WIKI="$wiki" python3 -I - <<'PYTHON'
import glob
import os
import re
import subprocess
import sys

racine = os.environ["RACINE"]
wiki = os.environ["WIKI"]
erreurs = 0


def lire(chemin):
    with open(chemin, encoding="utf-8") as fichier:
        return fichier.read()


def rapport(titre, anomalies):
    global erreurs
    if anomalies:
        erreurs += 1
        print(f"[ERREUR] {titre}")
        for anomalie in anomalies:
            print(f"    {anomalie}")
    else:
        print(f"[OK] {titre}")


pages = {os.path.splitext(os.path.basename(f))[0] for f in glob.glob(f"{wiki}/*.md")}
texte_wiki = {p: lire(f"{wiki}/{p}.md") for p in pages}

# 1. Liens internes du wiki
cassés = []
for page, texte in texte_wiki.items():
    for m in re.finditer(r"\[\[([^\]|#]+)(?:[#|][^\]]*)?\]\]", texte):
        nom = m.group(1).strip()
        if nom not in pages and nom.replace(" ", "-") not in pages:
            cassés.append(f"{page} : [[{nom}]]")
    for m in re.finditer(r"\]\(([^)\s]+)\)", texte):
        lien = m.group(1)
        if lien.startswith(("http", "#", "mailto")):
            continue
        base = lien.split("#")[0]
        if base and base not in pages and not os.path.exists(f"{wiki}/{base}"):
            cassés.append(f"{page} : ({lien})")
rapport("liens internes du wiki", cassés)

# 2. Routes : tout ce que Routes.md cite existe dans le code, tout contrôleur est décrit
sources = ""
for motif in ("java/web/src/main/java/**/*.java", "java/worker/src/main/java/**/*.java"):
    for fichier in glob.glob(f"{racine}/{motif}", recursive=True):
        sources += lire(fichier) + "\n"
routes_wiki = sorted(set(re.findall(r"`(/(?:api|login|logout|setup)[^`\s]*)`", texte_wiki.get("Routes", ""))))
absentes_du_code = []
for route in routes_wiki:
    segments = [s for s in route.split("/") if s and not s.startswith("{")]
    if segments and segments[-1] not in sources:
        absentes_du_code.append(route)
chemins_code = set()
for fichier in glob.glob(f"{racine}/java/web/src/main/java/**/*Controller.java", recursive=True):
    contenu = lire(fichier)
    base = re.search(r'@RequestMapping\(\s*(?:value\s*=\s*)?"([^"]*)"', contenu)
    prefixe = base.group(1) if base else ""
    for m in re.finditer(r'@(?:Get|Post|Put|Patch|Delete)Mapping(?:\(\s*(?:value\s*=\s*)?"([^"]*)")?', contenu):
        chemins_code.add((prefixe + (m.group(1) or "")).rstrip("/") or "/")


def sans_variables(chemin):
    return re.sub(r"\{[^}]*\}", "{}", chemin)


# Un chemin est décrit quand Routes.md le cite en entier, ou, pour une ligne qui regroupe
# plusieurs routes voisines (`/reset-mdp`, `/reset-totp`), par ses deux derniers segments.
texte_routes = sans_variables(texte_wiki.get("Routes", ""))
def est_decrit(chemin):
    complet = sans_variables(chemin)
    segments = [x for x in complet.split("/") if x]
    relatif = "`/" + segments[-1] + "`" if segments else ""
    return complet in texte_routes or "/".join(segments[-2:]) in texte_routes or relatif in texte_routes


non_decrites = sorted(c for c in chemins_code if not est_decrit(c))
rapport(
    "routes du wiki présentes dans le code",
    [f"{r} (citée par Routes.md, introuvable dans le code)" for r in absentes_du_code],
)
rapport(
    "routes du code décrites dans le wiki",
    [f"{c} (contrôleur sans ligne dans Routes.md)" for c in non_decrites],
)

# 3. Tables : migrations et Schéma-de-base-de-données.md
creees, supprimees = set(), set()
for fichier in sorted(glob.glob(f"{racine}/java/web/src/main/resources/db/migration/*.sql")):
    contenu = lire(fichier)
    creees |= set(re.findall(r"CREATE TABLE (?:IF NOT EXISTS )?(\w+)", contenu, re.I))
    supprimees |= set(re.findall(r"DROP TABLE (?:IF EXISTS )?(\w+)", contenu, re.I))
schema = texte_wiki.get("Schéma-de-base-de-données", "").lower()
manquantes = sorted(t for t in creees - supprimees if t.lower() not in schema)
rapport("tables des migrations décrites dans le schéma", [f"{t} (absente de la page Schéma)" for t in manquantes])

# 4. Versions annoncées
pom = lire(f"{racine}/java/pom.xml")
paquet = lire(f"{racine}/front/package.json")
claude = lire(f"{racine}/CLAUDE.md")
ecarts = []


def majeure(valeur):
    return valeur.lstrip("^~").split(".")[0]


parent = re.search(r"<artifactId>spring-boot-starter-parent</artifactId>\s*<version>([^<]+)", pom)
java = re.search(r"<java.version>(\d+)", pom)
flyway = re.search(r"<flyway.version>(\d+)", pom)
vite = re.search(r'"vite":\s*"([^"]+)"', paquet)
vitest = re.search(r'"vitest":\s*"([^"]+)"', paquet)
attendus = {
    "Spring Boot": parent.group(1) if parent else None,
    "Java": java.group(1) if java else None,
    "Flyway": flyway.group(1) if flyway else None,
    "Vite": majeure(vite.group(1)) if vite else None,
    "Vitest": majeure(vitest.group(1)) if vitest else None,
}
for nom, valeur in attendus.items():
    # Java : seule la ligne d'architecture fait foi, d'autres mentions sont historiques.
    if not valeur or nom == "Java":
        continue
    for m in re.finditer(rf"{re.escape(nom)} (\d+(?:\.\d+)*)", claude):
        cite = m.group(1)
        if not (valeur == cite or valeur.startswith(cite + ".")):
            ecarts.append(f"CLAUDE.md cite {nom} {cite}, le dépôt est en {valeur}")
ligne_java = re.search(r"Java (\d+), Spring Boot", claude)
if ligne_java and attendus["Java"] and ligne_java.group(1) != attendus["Java"]:
    ecarts.append(f"CLAUDE.md cite Java {ligne_java.group(1)}, le dépôt est en {attendus['Java']}")
architecture = texte_wiki.get("Architecture", "")
for nom, valeur in attendus.items():
    if not valeur:
        continue
    for m in re.finditer(rf"{re.escape(nom)} (\d+(?:\.\d+)*)", architecture):
        cite = m.group(1)
        if not (valeur == cite or valeur.startswith(cite + ".")):
            ecarts.append(f"Architecture.md cite {nom} {cite}, le dépôt est en {valeur}")
rapport("versions annoncées (CLAUDE.md, Architecture) comme celles du dépôt", sorted(set(ecarts)))

# 5. Roadmap : une entrée par version publiée, et par la version en cours
etiquettes = subprocess.check_output(["git", "-C", racine, "tag"], text=True).split()
roadmap = texte_wiki.get("Roadmap", "")
versions_roadmap = set(re.findall(r"^### (\d+\.\d+\.\d+)", roadmap, re.M))
revision = re.search(r"<revision>([^<]+)", pom)
attendues = {t[1:] for t in etiquettes if re.fullmatch(r"v\d+\.\d+\.\d+", t)}
# Une pré-version d'essai (suffixe) n'a ni entrée de Roadmap ni entrée de changelog.
version_finale = revision and "-" not in revision.group(1)
if version_finale:
    attendues.add(revision.group(1))
absentes = sorted(
    (v for v in attendues if v not in versions_roadmap),
    key=lambda v: [int(x) for x in v.split(".")],
)
rapport("versions publiées et en cours présentes dans la Roadmap", [f"{v} (aucune entrée « ### {v} »)" for v in absentes])

# 6. Changelog utilisateur de la version en cours
changelog = lire(f"{racine}/front/src/pages/changelogVersions.js")
manque = []
if version_finale and f"v {revision.group(1)} " not in changelog:
    manque.append(f"aucune entrée « v {revision.group(1)} » dans changelogVersions.js")
rapport("changelog utilisateur de la version en cours", manque)

sys.exit(1 if erreurs else 0)
PYTHON
