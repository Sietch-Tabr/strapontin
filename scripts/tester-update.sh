#!/bin/bash
# tester-update.sh — contrôle, sans systemd ni AIDE réel, que update.sh régénère la baseline AIDE
# après un déploiement réussi comme après un rollback (qui retire les .prev que la baseline
# attend), et la conserve quand un changement externe a déjà été détecté. Les commandes système
# sont remplacées par des substituts placés devant le PATH.
set -euo pipefail

RACINE="$(cd "$(dirname "$0")" && pwd)"
TMP="$(mktemp -d)"
export VERROU_DIR="$TMP/verrou"
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
for faux in systemctl journalctl sleep chown; do printf '#!/usr/bin/env bash\nexit 0\n' > "$FAUX/$faux"; done
chmod +x "$FAUX"/*
export PATH="$FAUX:$PATH"

echecs=0
verifier() { if ! "$@"; then echo "ECHEC : $*" >&2; echecs=$((echecs + 1)); fi; }
nombre_init() { if [ -f "$TMP_JOURNAL" ]; then wc -l < "$TMP_JOURNAL" | tr -d ' '; else echo 0; fi; }

somme() { if command -v sha256sum >/dev/null 2>&1; then sha256sum "$@"; else shasum -a 256 "$@"; fi; }

# Prépare un LXC factice : JARs actifs (v1), JARs précédents (v0), nouveaux JARs (v2), baseline, et
# les fichiers livrés (unité, configuration AIDE) avec leur manifeste.
preparer() {
    export APP_RACINE="$TMP/app" AIDE_BASE="$TMP/aide" AIDE_CONF="$TMP/aide.conf" TMP_JOURNAL="$TMP/journal"
    export APP_JARS="$APP_RACINE/application" APP_EXPLOITATION="$APP_RACINE/exploitation"
    export UNITES_DIR="$TMP/unites" ENV_FICHIER="$TMP/strapontin.env" VAR_RACINE="$TMP/var"
    rm -rf "$APP_RACINE" "$AIDE_BASE" "$TMP_JOURNAL" "$UNITES_DIR" "$VAR_RACINE"
    mkdir -p "$APP_JARS" "$APP_EXPLOITATION/systemd" "$AIDE_BASE" "$UNITES_DIR"
    echo "# configuration livrée" > "$APP_EXPLOITATION/aide.conf"
    echo "[Service]" > "$APP_EXPLOITATION/systemd/strapontin-web.service"
    cp "$APP_EXPLOITATION/aide.conf" "$AIDE_CONF"
    cp "$APP_EXPLOITATION/systemd/strapontin-web.service" "$UNITES_DIR/"
    (cd "$APP_EXPLOITATION" && somme aide.conf systemd/strapontin-web.service > MANIFESTE.sha256)
    : > "$ENV_FICHIER"
    for jar in web worker; do
        echo v1 > "$APP_JARS/$jar.jar"; echo v0 > "$APP_JARS/$jar.jar.prev"; echo v2 > "$APP_JARS/$jar.jar.new"
    done
    echo base-initiale > "$AIDE_BASE/aide.db"
    unset AIDE_RAPPORT AIDE_CODE SANTE
}
lancer() {
    bash "$RACINE/update.sh" > "$TMP/sortie" 2>&1 && CODE=0 || CODE=$?
    # Le verrou d'exploitation est libéré à la sortie, réussie ou non.
    [ ! -e "$VERROU_DIR" ] || { echo "ECHEC : verrou non libéré par update.sh" >&2; exit 1; }
}

# 1. Déploiement réussi : nouveaux JARs actifs, baseline régénérée.
preparer
lancer
verifier test "$CODE" = 0
verifier test "$(cat "$APP_JARS/web.jar")" = v2
verifier test "$(nombre_init)" = 1
verifier test "$(cat "$AIDE_BASE/aide.db")" = base-1

# 2. Santé en échec, aucun changement externe : retour aux JARs précédents, sans .prev, et
# baseline régénérée pour que le contrôle suivant ne signale pas leur disparition.
preparer
SANTE=down lancer
verifier test "$CODE" = 1
verifier test "$(cat "$APP_JARS/web.jar")" = v1
verifier test ! -e "$APP_JARS/web.jar.prev"
verifier test "$(nombre_init)" = 1
verifier test "$(cat "$AIDE_BASE/aide.db")" = base-1

# 3. Santé en échec avec un changement externe déjà détecté : baseline conservée, à authentifier.
preparer
AIDE_CODE=4 AIDE_RAPPORT="$(printf 'Added entries:\t1\n\nAdded entries:\n---\nf++++++++++++++++: /opt/strapontin/intrus')" \
    SANTE=down lancer
verifier test "$CODE" = 1
verifier test "$(cat "$APP_JARS/web.jar")" = v1
verifier test "$(nombre_init)" = 0
verifier test "$(cat "$AIDE_BASE/aide.db")" = base-initiale
verifier grep -q "NON régénérée" "$TMP/sortie"

# 4. Livraison dérivée du dépôt (fichier modifié après le dépôt du manifeste) : le déploiement
# s'arrête avant tout remplacement, les JARs restent ceux d'avant.
preparer
echo "modification locale" >> "$APP_EXPLOITATION/aide.conf"
lancer
verifier test "$CODE" = 1
verifier test "$(cat "$APP_JARS/web.jar")" = v1
verifier test -e "$APP_JARS/web.jar.new"
verifier grep -q "aide.conf" "$TMP/sortie"
verifier grep -q "différents du dépôt" "$TMP/sortie"

# 5. Unité installée différente de celle livrée : écart signalé puis corrigé, déploiement réussi.
preparer
echo "[Service] modifiée à la main" > "$UNITES_DIR/strapontin-web.service"
lancer
verifier test "$CODE" = 0
verifier grep -q "DÉRIVE corrigée" "$TMP/sortie"
verifier cmp -s "$APP_EXPLOITATION/systemd/strapontin-web.service" "$UNITES_DIR/strapontin-web.service"

# 6. Outils d'exploitation livrés (.new : mise à jour, commandes de contrôle) : mis en place après un déploiement réussi, absent sans lui,
# donc sans effet pour les livraisons qui ne le portent pas (cas 1 à 5).
preparer
for outil in mise-a-jour exploitation-controle; do
    echo m1 > "$APP_JARS/$outil.jar"; echo m2 > "$APP_JARS/$outil.jar.new"
done
lancer
verifier test "$CODE" = 0
for outil in mise-a-jour exploitation-controle; do
    verifier test "$(cat "$APP_JARS/$outil.jar")" = m2
    verifier test ! -e "$APP_JARS/$outil.jar.new"
done
verifier test "$(cat "$AIDE_BASE/aide.db")" = base-1

# 7a. Préparation de la mise à jour depuis l'administration : à chaque déploiement, avant le
# redémarrage ; son échec n'arrête pas le déploiement.
preparer
lancer
verifier test "$CODE" = 0
verifier test -d "$VAR_RACINE/demandes"
verifier grep -q '^UPDATE_INTERNAL_SECRET=' "$ENV_FICHIER"
verifier grep -q "Dossiers, secret et minuterie prêts" "$TMP/sortie"
preparer
printf '#!/usr/bin/env bash\nexit 1\n' > "$FAUX/openssl"; chmod +x "$FAUX/openssl"
lancer
rm -f "$FAUX/openssl"
verifier test "$CODE" = 0
verifier grep -q "AVERTISSEMENT : préparation de la mise à jour" "$TMP/sortie"
verifier test "$(cat "$APP_JARS/web.jar")" = v2

# 7. Rollback : le module de mise à jour n'est pas remplacé, son .new reste pour la livraison suivante.
preparer
for outil in mise-a-jour exploitation-controle; do
    echo m1 > "$APP_JARS/$outil.jar"; echo m2 > "$APP_JARS/$outil.jar.new"
done
SANTE=down lancer
verifier test "$CODE" = 1
for outil in mise-a-jour exploitation-controle; do
    verifier test "$(cat "$APP_JARS/$outil.jar")" = m1
    verifier test -e "$APP_JARS/$outil.jar.new"
done

if [ "$echecs" -ne 0 ]; then
    echo "$echecs contrôle(s) en échec" >&2
    exit 1
fi
echo "update.sh : baseline AIDE (déploiement, rollback, changement externe), livraison contrôlée, dérive d'unité corrigée, module de mise à jour mis en place"
