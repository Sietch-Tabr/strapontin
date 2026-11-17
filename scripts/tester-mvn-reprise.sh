#!/bin/bash
# tester-mvn-reprise.sh — contrôle mvn-reprise.sh avec un faux mvn : reprise après un 429, aucune
# reprise sur un autre échec, abandon au nombre d'essais maximal, code de sortie conservé.
set -euo pipefail

RACINE="$(cd "$(dirname "$0")" && pwd)"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
mkdir "$TMP/bin"
export PATH="$TMP/bin:$PATH" DELAI_REPRISE=0 COMPTEUR="$TMP/compteur"
echecs=0

# Faux mvn : MODE=429_PUIS_OK (429 au premier appel), TOUJOURS_429, ECHEC_TEST (échec ordinaire).
cat > "$TMP/bin/mvn" <<'FAUX'
#!/bin/bash
n=$(( $(cat "$COMPTEUR" 2>/dev/null || echo 0) + 1 ))
echo "$n" > "$COMPTEUR"
case "$MODE" in
  429_PUIS_OK) if [ "$n" -eq 1 ]; then echo "[WARNING] Could not transfer artifact: HTTP Status: 429"; exit 1; fi ;;
  TOUJOURS_429) echo "[WARNING] Could not transfer artifact: HTTP Status: 429"; exit 1 ;;
  ECHEC_TEST) echo "[ERROR] Tests run: 3, Failures: 1"; exit 1 ;;
esac
exit 0
FAUX
chmod +x "$TMP/bin/mvn"

lancer() { rm -f "$COMPTEUR"; MODE="$1" bash "$RACINE/mvn-reprise.sh" test > "$TMP/sortie.txt" 2>&1; echo $?; }
verifier() { if [ "$1" != "$2" ]; then echo "ECHEC : $3 (attendu $2, obtenu $1)" >&2; echecs=$((echecs + 1)); fi; }

verifier "$(lancer 429_PUIS_OK)" 0 "reprise après un 429"
verifier "$(cat "$COMPTEUR")" 2 "deux appels après un 429"
verifier "$(grep -c 'nouvel essai' "$TMP/sortie.txt")" 1 "la reprise est annoncée"
verifier "$(lancer ECHEC_TEST)" 1 "un échec de test garde son code"
verifier "$(cat "$COMPTEUR")" 1 "aucune reprise sur un échec de test"
verifier "$(lancer TOUJOURS_429)" 1 "abandon après les essais"
verifier "$(cat "$COMPTEUR")" 3 "trois essais au plus"
verifier "$(lancer RIEN)" 0 "succès direct"
verifier "$(cat "$COMPTEUR")" 1 "un seul appel en cas de succès"
[ "$echecs" -eq 0 ] && echo "tester-mvn-reprise : OK" || { echo "tester-mvn-reprise : $echecs échec(s)" >&2; exit 1; }
