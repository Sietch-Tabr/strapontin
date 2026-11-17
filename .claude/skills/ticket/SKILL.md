---
name: Création de ticket
description: >
  Crée un ticket GitHub du dépôt au format du projet : constat, analyse, attendu, sans
  piste de solution ; étiquette vérifiée, jalon par --milestone, doublon recherché avant
  création.
when_to_use: >
  Quand l'utilisateur demande de créer, rédiger ou « ticketer » un ou plusieurs tickets,
  ou valide un brouillon de ticket proposé dans la conversation.
---

Les tickets du dépôt sont publics : tout ce qui y est écrit est publié.

## 1. Chercher un doublon

Avant toute rédaction, chercher un ticket existant sur le même sujet, ouvert ou fermé :

```bash
gh issue list --state all --search "<mot-clé>" --limit 100 --json number,title,state,milestone
```

Pour un jalon donné : `gh issue list --state all --milestone "<jalon>" --limit 1000 --json number --jq '[.[].number]|sort'` (`gh` ne retourne que 30 résultats sans `--limit`).

Lire un ticket candidat en entier, commentaires compris (`gh issue view N --comments`) : un ticket proche peut déjà couvrir le constat, qui s'ajoute alors à son corps, jamais en commentaire.

## 2. Rédiger

Trois sections, dans cet ordre, rien d'autre :

- **Constat** : ce qui a été observé, avec la preuve (requête, log, fichier et ligne, capture). Si le problème existe depuis longtemps sans avoir été vu, le dire : depuis quand, et pourquoi rien ne l'a détecté.
- **Analyse** : cause racine si elle est établie, mécanisme et code en cause. Distinguer ce qui est prouvé de ce qui est supposé.
- **Attendu** : le comportement ou l'état souhaité, jamais la manière d'y arriver. Si le problème est resté invisible, l'attendu inclut sa détection.

Jamais de « piste de correction » ni de « correctif proposé » : la solution se décide dans le jalon, avec tout son contexte. Seule exception : le compte rendu d'une correction de données déjà exécutée en prod.

Un constat mineur se ticket quand même, sa gravité indiquée dans le constat ; seul un faux positif prouvé n'est pas ticketé. Une faille de sécurité se ticket aussi publiquement, au même format (choix du projet) : le signaler dans la réponse.

Titre : court et descriptif, sans numéro de jalon ni de version.

## 3. Faire valider

Présenter le brouillon complet (titre, étiquette, jalon, corps) et attendre une validation explicite. Une demande urgente ou une question voisine ne vaut pas accord de création.

## 4. Créer et vérifier

Étiquettes du dépôt : `bug`, `enhancement` (ancien Feature et Task), `question`, `duplicate`, `invalid`.

```bash
gh issue create --title "<titre>" --body-file <fichier.md> --label "<étiquette>" --milestone "<jalon>"
```

Vérifier côté serveur que l'étiquette et le jalon sont bien posés :

```bash
gh issue view <N> --json number,labels,milestone --jq '[.number,[.labels[].name],.milestone.title]'
```

À défaut, corriger avec `gh issue edit <N> --add-label "<étiquette>" --milestone "<jalon>"`. Un nom d'étiquette ou de jalon inconnu fait échouer `gh` avec une erreur : la lire, ne jamais conclure sans relire l'état du ticket.

## Ne jamais

- Commenter, fermer ou changer le statut d'un ticket sans demande explicite ; un commentaire demandé se montre d'abord dans la conversation.
- Écrire un commentaire autrement que par `gh issue comment <N> --body-file fichier.md` (le contenu en fichier, jamais en argument).
- Déplacer un ticket d'un jalon dont l'échéance est aujourd'hui (elle court jusqu'à 23:59).
- Référencer un ticket dans le code source : seulement dans les commits et les PR.
