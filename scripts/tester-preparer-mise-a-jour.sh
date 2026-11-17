#!/bin/bash
# tester-preparer-mise-a-jour.sh — contrôle, sans LXC ni systemd, preparer-mise-a-jour.sh : dossiers
# créés avec leurs droits et propriétaires, secret créé une seule fois et jamais écrasé, droits du
# fichier d'environnement conservés, minuterie activée, refus sans fichier d'environnement. Les
# commandes système (chown, systemctl) sont remplacées par des substituts qui les journalisent.
set -euo pipefail

RACINE="$(cd "$(dirname "$0")" && pwd)"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
FAUX="$TMP/faux"
mkdir -p "$FAUX"
export JOURNAL="$TMP/journal"
for faux in chown systemctl; do
    printf '#!/usr/bin/env bash\necho "%s $*" >> "$JOURNAL"\n' "$faux" > "$FAUX/$faux"
done
chmod +x "$FAUX"/*
export PATH="$FAUX:$PATH"
export VAR_RACINE="$TMP/var" ENV_FICHIER="$TMP/strapontin.env"

echecs=0
verifier() { if ! "$@"; then echo "ECHEC : $*" >&2; echecs=$((echecs + 1)); fi; }
droits() { ls -ld "$1" | cut -c1-10; }
lancer() { bash "$RACINE/preparer-mise-a-jour.sh" > "$TMP/sortie" 2>&1 && CODE=0 || CODE=$?; }

# 1. Sans fichier d'environnement : refus, rien n'est créé.
lancer
verifier test "$CODE" -ne 0
verifier test ! -e "$VAR_RACINE"

# 2. Première préparation : dossiers, droits, propriétaires, secret, minuterie.
echo "SPRING_PROFILES_ACTIVE=dev" > "$ENV_FICHIER"
chmod 600 "$ENV_FICHIER"
lancer
verifier test "$CODE" = 0
verifier test "$(droits "$VAR_RACINE/demandes")" = "drwxr-x---"
verifier test "$(droits "$VAR_RACINE/etat")" = "drwxr-xr-x"
verifier test "$(droits "$VAR_RACINE/telechargements")" = "drwx------"
verifier test "$(droits "$VAR_RACINE/sigstore")" = "drwx------"
verifier grep -q "chown strapontin:strapontin $VAR_RACINE/demandes" "$JOURNAL"
verifier grep -q "chown root:root $VAR_RACINE/etat" "$JOURNAL"
SECRET="$(grep '^UPDATE_INTERNAL_SECRET=' "$ENV_FICHIER" | cut -d= -f2-)"
verifier test "${#SECRET}" -eq 64
verifier grep -q '^SPRING_PROFILES_ACTIVE=dev$' "$ENV_FICHIER"
verifier test "$(droits "$ENV_FICHIER")" = "-rw-------"
verifier grep -q "systemctl daemon-reload" "$JOURNAL"
verifier grep -q "systemctl enable --now strapontin-mise-a-jour.path" "$JOURNAL"
verifier grep -q "UPDATE_INTERNAL_SECRET créé" "$TMP/sortie"

# 3. Seconde préparation : le secret n'est ni recréé ni écrasé, rien d'autre ne change.
lancer
verifier test "$CODE" = 0
verifier test "$(grep -c '^UPDATE_INTERNAL_SECRET=' "$ENV_FICHIER")" = 1
verifier test "$(grep '^UPDATE_INTERNAL_SECRET=' "$ENV_FICHIER" | cut -d= -f2-)" = "$SECRET"
verifier test "$(grep -c 'UPDATE_INTERNAL_SECRET créé' "$TMP/sortie")" = 0
verifier test "$(droits "$ENV_FICHIER")" = "-rw-------"

# 4. Un dossier existant aux mauvais droits est corrigé.
chmod 777 "$VAR_RACINE/demandes"
lancer
verifier test "$(droits "$VAR_RACINE/demandes")" = "drwxr-x---"

if [ "$echecs" -ne 0 ]; then
    echo "$echecs contrôle(s) en échec" >&2
    exit 1
fi
echo "preparer-mise-a-jour.sh : dossiers et droits, secret créé une fois, minuterie activée, idempotent"
