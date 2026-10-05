#!/bin/sh
# Garde-fou de l'outillage Claude Code versionné (.claude/) : aucune donnée de prod, aucun
# accès à l'infrastructure, aucun chemin ni configuration propre à un poste, et aucun renvoi
# de CLAUDE.md vers un fichier .claude absent du dépôt. Exécuté par la CI (lint-front).
#
# Usage : sh scripts/verifier-outillage.sh (depuis la racine du dépôt). POSIX sh : l'image
# node alpine de la CI ne fournit pas bash.

set -eu

if git rev-parse --is-inside-work-tree >/dev/null 2>&1; then
  avec_git=1
  fichiers=$(git ls-files .claude)
else
  # Clone de CI sans git : seuls les fichiers versionnés sont présents.
  avec_git=0
  fichiers=$(find .claude -type f)
fi

versionne() {
  if [ "$avec_git" -eq 1 ]; then
    git ls-files --error-unmatch "$1" >/dev/null 2>&1
  else
    [ -f "$1" ]
  fi
}

erreurs=0
signaler() {
  echo "verifier-outillage : $1" >&2
  erreurs=$((erreurs + 1))
}

for fichier in $fichiers; do
  case "$fichier" in
    */snapshots/* | *curseurs_rotation.json | *decrypt_config.py | *settings.local.json \
      | */agent-memory/* | *.local.* | *__pycache__*)
      signaler "fichier personnel ou de prod versionné : $fichier"
      continue
      ;;
  esac
  # Chemin personnel, réseau d'administration de l'hyperviseur, jeton JWT, clé privée.
  if grep -nE '/Users/|/home/[a-z]|192\.168\.1\.|eyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}|BEGIN [A-Z ]*PRIVATE KEY' "$fichier" >/dev/null 2>&1; then
    signaler "contenu interdit dans $fichier :"
    grep -nE '/Users/|/home/[a-z]|192\.168\.1\.|eyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}|BEGIN [A-Z ]*PRIVATE KEY' "$fichier" | head -5 >&2
  fi
done

# Chaque fichier .claude cité par un CLAUDE.md du dépôt doit exister, hors fichiers locaux
# (*.local.*) dont l'absence du dépôt est voulue et documentée.
for doc in $(find . -name CLAUDE.md -not -path './node_modules/*' -not -path '*/node_modules/*' -not -path './.claude/*' -not -path '*/target/*'); do
  for reference in $(grep -oE '\.claude/[A-Za-z0-9_./-]+\.(md|sh|py|sql|json)' "$doc" | sort -u); do
    case "$reference" in *.local.*) continue ;; esac
    if ! versionne "$reference"; then
      signaler "$doc renvoie à $reference, absent du dépôt"
    fi
  done
done

if [ "$erreurs" -gt 0 ]; then
  echo "verifier-outillage : $erreurs problème(s)" >&2
  exit 1
fi
echo "verifier-outillage : OK"
