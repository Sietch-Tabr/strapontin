---
name: Création de ticket
description: >
  Crée un ticket Codeberg (Kwaite/strapontin) au format du projet : constat, analyse,
  attendu, sans piste de solution ; étiquette vérifiée, jalon par --milestone, doublon
  recherché avant création.
when_to_use: >
  Quand l'utilisateur demande de créer, rédiger ou « ticketer » un ou plusieurs tickets,
  ou valide un brouillon de ticket proposé dans la conversation.
---

Les tickets du dépôt sont publics : tout ce qui y est écrit est publié.

## 1. Chercher un doublon

Avant toute rédaction, chercher un ticket existant sur le même sujet, ouvert ou fermé :

```bash
tea issues -r Kwaite/strapontin --state all -k "<mot-clé>" -f "index,title,state,milestone"
```

Pour un jalon donné, `tea issues --milestones` peut sous-compter silencieusement un gros jalon : croiser avec l'API.

```bash
curl -s --max-time 20 "https://codeberg.org/api/v1/repos/Kwaite/strapontin/issues?milestones=<jalon>&state=all&limit=50" \
  | python3 -c "import json,sys; print(sorted(i['number'] for i in json.load(sys.stdin)))"
```

Lire un ticket candidat en entier, commentaires compris (`tea issue view N -f "index,title,body"` puis l'API `/issues/N/comments`) : un ticket proche peut déjà couvrir le constat, qui s'ajoute alors à son corps, jamais en commentaire.

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

Étiquettes existantes, sensibles à la casse : `Bug`, `Feature`, `Task`, `Question`, `Duplicate`, `Invalid`. Un nom invalide est ignoré sans erreur par tea.

```bash
tea issues create -r Kwaite/strapontin --title "<titre>" --description-file <fichier.md> \
  --labels "<Étiquette>" --milestone "<jalon>"
```

Vérifier côté serveur que l'étiquette et le jalon sont bien posés :

```bash
curl -s --max-time 20 "https://codeberg.org/api/v1/repos/Kwaite/strapontin/issues/<N>" \
  | python3 -c "import json,sys; d=json.load(sys.stdin); print(d['number'], [l['name'] for l in d['labels']], (d['milestone'] or {}).get('title'))"
```

À défaut, corriger avec `tea issues edit <N> -r Kwaite/strapontin --add-labels "<Étiquette>" --milestone "<jalon>"`.

## Ne jamais

- Commenter, fermer ou changer le statut d'un ticket sans demande explicite ; un commentaire demandé se montre d'abord dans la conversation.
- Utiliser `tea comment` (bloque en lisant stdin) : `tea api --method POST "/repos/Kwaite/strapontin/issues/<N>/comments" -f "body=$(cat fichier.md)"`.
- Déplacer un ticket d'un jalon dont l'échéance est aujourd'hui (elle court jusqu'à 23:59).
- Référencer un ticket dans le code source : seulement dans les commits et les PR.
