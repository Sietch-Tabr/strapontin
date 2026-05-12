---
paths:
  - "**/*.ts"
  - "**/*.svelte"
---

# TypeScript / Svelte — vérification avant commit

Après chaque modification d'un fichier `.ts` ou `.svelte` :

1. Utiliser l'outil LSP pour vérifier les diagnostics sur le fichier modifié
2. Si des erreurs sont détectées : les corriger avant de continuer
3. Ne jamais proposer un commit avec des erreurs LSP en suspens
