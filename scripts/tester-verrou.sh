#!/bin/bash
# tester-verrou.sh — contrôle, sans LXC ni systemd, le verrou d'exploitation de verrou.sh : prise et
# libération, refus aussitôt quand un propriétaire vivant le tient, reprise d'un verrou périmé,
# héritage par un processus fils, libération réservée au propriétaire. Le test croisé avec la
# classe Java Verrou est dans les tests de la bibliothèque exploitation-commun.
set -euo pipefail

RACINE="$(cd "$(dirname "$0")" && pwd)"
TMP="$(mktemp -d)"
trap 'kill $(jobs -p) 2>/dev/null || true; rm -rf "$TMP"' EXIT
export VERROU_DIR="$TMP/verrou"
echecs=0
verifier() { if ! "$@"; then echo "ECHEC : $*" >&2; echecs=$((echecs + 1)); fi; }

# Exécute des commandes dans un shell neuf qui a chargé verrou.sh.
dans_un_shell() { bash -c ". '$RACINE/verrou.sh'; $1" 2>&1; }

# 1. Prise puis libération : le répertoire existe pendant, plus après.
verifier dans_un_shell 'prendre_verrou essai && test -d "$VERROU_DIR" && liberer_verrou && test ! -e "$VERROU_DIR"' > /dev/null
verifier test ! -e "$VERROU_DIR"

# 2. Le fichier du propriétaire porte PID, heure de démarrage et opération.
dans_un_shell 'prendre_verrou ma-operation; cat "$VERROU_DIR/proprietaire"; liberer_verrou' > "$TMP/proprietaire.txt"
verifier test "$(sed -n 1p "$TMP/proprietaire.txt")" -gt 0
verifier test "$(sed -n 3p "$TMP/proprietaire.txt")" = "ma-operation"

# 3. Un propriétaire vivant fait refuser aussitôt (code 1), le verrou reste en place.
bash -c ". '$RACINE/verrou.sh'; prendre_verrou longue-operation; sleep 30" &
for _ in $(seq 50); do [ -f "$VERROU_DIR/proprietaire" ] && break; sleep 0.1; done
SORTIE="$(dans_un_shell 'prendre_verrou concurrente' || echo "code=$?")"
verifier test "${SORTIE##*code=}" = "1"
case "$SORTIE" in *longue-operation*) ;; *) echo "ECHEC : le refus ne nomme pas l'opération en cours" >&2; echecs=$((echecs + 1)) ;; esac
verifier test -d "$VERROU_DIR"

# 4. Hérité : un processus fils reconnaît le verrou de son parent sans le reprendre ni le libérer.
verifier dans_un_shell 'VERROU_HERITE=1 prendre_verrou fils && VERROU_HERITE=1 liberer_verrou && test -d "$VERROU_DIR"' > /dev/null
kill $(jobs -p) 2>/dev/null || true
wait 2>/dev/null || true
rm -rf "$VERROU_DIR"

# 5. Hérité sans verrou : refus.
SORTIE="$(dans_un_shell 'VERROU_HERITE=1 prendre_verrou fils' || echo "code=$?")"
verifier test "${SORTIE##*code=}" = "1"

# 6. Propriétaire disparu : verrou périmé, repris.
PIDMORT="$(bash -c 'echo $$')"
mkdir "$VERROU_DIR"
printf '%s\n%s\n%s\n' "$PIDMORT" "-" "ancienne-operation" > "$VERROU_DIR/proprietaire"
SORTIE="$(dans_un_shell 'prendre_verrou reprise && sed -n 3p "$VERROU_DIR/proprietaire"; liberer_verrou')"
case "$SORTIE" in *"Verrou périmé écarté"*reprise*) ;; *) echo "ECHEC : verrou périmé non repris ($SORTIE)" >&2; echecs=$((echecs + 1)) ;; esac
verifier test ! -e "$VERROU_DIR"

# 7. Heure de démarrage différente (PID réutilisé) : périmé, sous Linux seulement.
if [ -r /proc/self/stat ]; then
    mkdir "$VERROU_DIR"
    printf '%s\n%s\n%s\n' "$$" "1" "pid-reutilise" > "$VERROU_DIR/proprietaire"
    SORTIE="$(dans_un_shell 'prendre_verrou reprise; liberer_verrou')"
    case "$SORTIE" in *"Verrou périmé écarté"*) ;; *) echo "ECHEC : PID réutilisé non détecté ($SORTIE)" >&2; echecs=$((echecs + 1)) ;; esac
    verifier test ! -e "$VERROU_DIR"
fi

# 8. Répertoire sans fichier de propriétaire, récent : en cours de création, refus.
mkdir "$VERROU_DIR"
SORTIE="$(dans_un_shell 'prendre_verrou concurrente' || echo "code=$?")"
verifier test "${SORTIE##*code=}" = "1"
rm -rf "$VERROU_DIR"

# 9. Libérer ne supprime pas le verrou d'un autre propriétaire.
mkdir "$VERROU_DIR"
printf '%s\n%s\n%s\n' "999999" "-" "autre" > "$VERROU_DIR/proprietaire"
dans_un_shell 'VERROU_PRIS=1; liberer_verrou' > /dev/null
verifier test -d "$VERROU_DIR"
rm -rf "$VERROU_DIR"

if [ "$echecs" -ne 0 ]; then
    echo "$echecs contrôle(s) en échec" >&2
    exit 1
fi
echo "verrou : prise, refus d'un propriétaire vivant, reprise d'un verrou périmé, héritage, libération"
