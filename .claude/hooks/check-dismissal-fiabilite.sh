#!/bin/bash
# Garde-fou pour le skill /suivi-fiabilite : intercepte toute écriture dans
# project_suivi_fiabilite_prod.md contenant une tournure de rejet ("faux positif",
# "bénin", "sans conséquence", "déjà connu"...) et force une escalade avant
# d'accepter l'écriture, plutôt que de laisser une conclusion non vérifiée
# passer silencieusement. Voir HISTORIQUE.md du skill pour le contexte
# (récidives répétées de dismissal sans vérification individuelle).

INPUT=$(cat)
FILE_PATH=$(echo "$INPUT" | jq -r '.tool_input.file_path // empty')

case "$FILE_PATH" in
  */project_suivi_fiabilite_prod.md) ;;
  *) exit 0 ;;
esac

TOOL_NAME=$(echo "$INPUT" | jq -r '.tool_name // empty')
if [ "$TOOL_NAME" = "Write" ]; then
  TEXT=$(echo "$INPUT" | jq -r '.tool_input.content // empty')
elif [ "$TOOL_NAME" = "Edit" ]; then
  TEXT=$(echo "$INPUT" | jq -r '.tool_input.new_string // empty')
else
  exit 0
fi

if echo "$TEXT" | grep -iqE 'faux positif|bénin|sans conséquence|déjà connu|pattern connu|rien d.anormal|comportement attendu'; then
  REASON="Ce texte contient une tournure de rejet (faux positif/benin/deja connu/sans consequence). As-tu re-derive cette conclusion avec une preuve fraiche (requete SQL, appel Wikidata, log) dans ce tour, ou t-appuies-tu sur une classification anterieure sans reverification ?"
  jq -n --arg reason "$REASON" '{
    hookSpecificOutput: {
      hookEventName: "PreToolUse",
      permissionDecision: "ask",
      permissionDecisionReason: $reason
    }
  }'
fi
exit 0
