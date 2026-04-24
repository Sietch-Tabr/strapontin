#!/usr/bin/env tsx
// Génère une paire de clés VAPID pour les notifications Web Push.
// Usage : tsx scripts/generer-vapid.ts
// → Affiche les clés à coller dans l'IHM admin (app_config).

import { generateVAPIDKeys } from "web-push";

const cles = generateVAPIDKeys();

console.log("Clés VAPID générées :");
console.log("");
console.log("VAPID_PUBLIC_KEY  =", cles.publicKey);
console.log("VAPID_PRIVATE_KEY =", cles.privateKey);
console.log("");
console.log(
  "→ Coller ces valeurs dans l'IHM admin (section Configuration).",
);
console.log(
  "→ La clé publique est également nécessaire dans PUBLIC_VAPID_KEY.",
);
