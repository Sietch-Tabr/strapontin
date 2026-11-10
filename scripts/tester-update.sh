#!/bin/bash
# tester-update.sh — contrôle, sans systemd ni AIDE réel, que update.sh régénère la baseline AIDE
# après un déploiement réussi comme après un rollback (qui retire les .prev que la baseline
# attend), et la conserve quand un changement externe a déjà été détecté. Les commandes système
# sont remplacées par des substituts placés devant le PATH.
set -euo pipefail

RACINE="$(cd "$(dirname "$0")" && pwd)"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
FAUX="$TMP/faux"
mkdir -p "$FAUX"

# aide : --check rend le rapport et le code choisis par le scénario, --init pose une nouvelle base.
cat > "$FAUX/aide" <<'FIN'
#!/usr/bin/env bash
case "$*" in
    *--init*) echo init >> "$TMP_JOURNAL"; echo "base-$(wc -l < "$TMP_JOURNAL" | tr -d ' ')" >"$AIDE_BASE/aide.db.new" ;;
    *--check*) [ -n "${AIDE_RAPPORT:-}" ] && printf '%s\n' "$AIDE_RAPPORT"; exit "${AIDE_CODE:-0}" ;;
esac
FIN
# curl : la santé du web répond UP ou non selon le scénario ; tout autre appel est ignoré.
cat > "$FAUX/curl" <<'FIN'
#!/usr/bin/env bash
case "$*" in
    *actuator/health*) [ "${SANTE:-up}" = up ] && echo '{"status":"UP"}' || echo '{"status":"DOWN"}' ;;
esac
exit 0
FIN
for faux in systemctl journalctl sleep; do printf '#!/usr/bin/env bash\nexit 0\n' > "$FAUX/$faux"; done
chmod +x "$FAUX"/*
export PATH="$FAUX:$PATH"

echecs=0
verifier() { if ! "$@"; then echo "ECHEC : $*" >&2; echecs=$((echecs + 1)); fi; }
nombre_init() { if [ -f "$TMP_JOURNAL" ]; then wc -l < "$TMP_JOURNAL" | tr -d ' '; else echo 0; fi; }

# Prépare un LXC factice : JARs actifs (v1), JARs précédents (v0), nouveaux JARs (v2), baseline.
preparer() {
    export APP_DIR="$TMP/app" AIDE_BASE="$TMP/aide" AIDE_CONF="$TMP/aide.conf" TMP_JOURNAL="$TMP/journal"
    rm -rf "$APP_DIR" "$AIDE_BASE" "$TMP_JOURNAL"
    mkdir -p "$APP_DIR/scripts" "$AIDE_BASE"
    : > "$AIDE_CONF"
    : > "$APP_DIR/.env"
    for jar in web worker; do
        echo v1 > "$APP_DIR/$jar.jar"; echo v0 > "$APP_DIR/$jar.jar.prev"; echo v2 > "$APP_DIR/$jar.jar.new"
    done
    echo base-initiale > "$AIDE_BASE/aide.db"
    unset AIDE_RAPPORT AIDE_CODE SANTE
}
lancer() { bash "$RACINE/update.sh" > "$TMP/sortie" 2>&1 && CODE=0 || CODE=$?; }

# 1. Déploiement réussi : nouveaux JARs actifs, baseline régénérée.
preparer
lancer
verifier test "$CODE" = 0
verifier test "$(cat "$APP_DIR/web.jar")" = v2
verifier test "$(nombre_init)" = 1
verifier test "$(cat "$AIDE_BASE/aide.db")" = base-1

# 2. Santé en échec, aucun changement externe : retour aux JARs précédents, sans .prev, et
# baseline régénérée pour que le contrôle suivant ne signale pas leur disparition.
preparer
SANTE=down lancer
verifier test "$CODE" = 1
verifier test "$(cat "$APP_DIR/web.jar")" = v1
verifier test ! -e "$APP_DIR/web.jar.prev"
verifier test "$(nombre_init)" = 1
verifier test "$(cat "$AIDE_BASE/aide.db")" = base-1

# 3. Santé en échec avec un changement externe déjà détecté : baseline conservée, à authentifier.
preparer
AIDE_CODE=4 AIDE_RAPPORT="$(printf 'Added entries:\t1\n\nAdded entries:\n---\nf++++++++++++++++: /opt/strapontin/intrus')" \
    SANTE=down lancer
verifier test "$CODE" = 1
verifier test "$(cat "$APP_DIR/web.jar")" = v1
verifier test "$(nombre_init)" = 0
verifier test "$(cat "$AIDE_BASE/aide.db")" = base-initiale
verifier grep -q "NON régénérée" "$TMP/sortie"

if [ "$echecs" -ne 0 ]; then
    echo "$echecs contrôle(s) en échec" >&2
    exit 1
fi
echo "update.sh : baseline AIDE régénérée au déploiement et au rollback, conservée sur changement externe"
