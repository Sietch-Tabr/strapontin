#!/usr/bin/env node
// Génère une paire de clés VAPID pour les notifications Web Push.
// Usage : node scripts/generer-vapid.mjs
// → Affiche les clés à coller dans l'IHM admin (section Configuration).

import { createECDH } from "node:crypto";

const ecdh = createECDH("prime256v1");
ecdh.generateKeys();

const publicKey = ecdh.getPublicKey("base64url");
const privateKey = ecdh.getPrivateKey("base64url");

console.log("Clés VAPID générées :");
console.log("");
console.log("VAPID_PUBLIC_KEY  =", publicKey);
console.log("VAPID_PRIVATE_KEY =", privateKey);
console.log("");
console.log("→ Coller ces valeurs dans l'IHM admin (section Configuration).");
