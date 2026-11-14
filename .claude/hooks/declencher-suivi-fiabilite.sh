#!/bin/bash
# Un prompt réduit à "date" doit lancer le check complet /suivi-fiabilite
# (règle ignorée à plusieurs reprises). Ne couvre pas "!date" : le mode shell
# ne déclenche aucun hook.

PROMPT=$(jq -r '.prompt // empty')
NORMALISE=$(echo "$PROMPT" | tr '[:upper:]' '[:lower:]' | tr -d '[:space:]')

[ "$NORMALISE" = "date" ] || exit 0

jq -n '{hookSpecificOutput: {hookEventName: "UserPromptSubmit", additionalContext: "Commande date reçue dans ce projet : le check complet /suivi-fiabilite (skill) est requis dans ce tour. Invoquer le skill avant toute réponse, sans répondre l heure et sans demander confirmation."}}'
