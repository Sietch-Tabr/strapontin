// Vérifie le service worker construit (static/sw.js) en conditions réelles : enregistrement,
// activation, précache, puis rechargement hors ligne. Les E2E ne peuvent pas le faire : le dev est
// servi en HTTP sur une IP privée, contexte non sécurisé où l'API Service Worker n'existe pas.
// Ici le dossier statique est servi sur localhost, contexte sécurisé.
//
// Usage (depuis e2e/, après `npm run build` dans front/) :
//   node outils/verifier-service-worker.mjs [dossier-statique] [port]
// Défaut : ../java/web/src/main/resources/static, port 18431. Comparer deux builds = deux appels
// avec deux dossiers. Code de sortie 1 si le rechargement hors ligne échoue ou si le précache est vide.
import { chromium } from "@playwright/test";
import { createServer } from "node:http";
import { readFile } from "node:fs/promises";
import { extname, join, normalize } from "node:path";
import { fileURLToPath } from "node:url";

const dossier = process.argv[2] ?? fileURLToPath(new URL("../../java/web/src/main/resources/static", import.meta.url));
const port = Number(process.argv[3] ?? 18431);
const types = { ".html": "text/html", ".js": "text/javascript", ".css": "text/css", ".png": "image/png", ".webp": "image/webp", ".svg": "image/svg+xml" };

const serveur = createServer(async (req, res) => {
  const chemin = new URL(req.url, "http://x").pathname;
  if (chemin.startsWith("/api/")) return void res.writeHead(503).end();
  const cible = chemin === "/" ? "/index.html" : chemin;
  try {
    const contenu = await readFile(join(dossier, normalize(cible)));
    res.writeHead(200, { "content-type": types[extname(cible)] ?? "application/octet-stream", "cache-control": "no-cache" });
    res.end(contenu);
  } catch {
    res.writeHead(404).end();
  }
});
await new Promise((ok) => serveur.listen(port, "127.0.0.1", ok));

const navigateur = await chromium.launch();
const contexte = await navigateur.newContext();
const page = await contexte.newPage();
await page.goto(`http://localhost:${port}/`);
await page.waitForFunction(async () => (await navigator.serviceWorker.getRegistration("/"))?.active?.state === "activated", null, { timeout: 20000 });
await page.waitForTimeout(1500);

const caches = await page.evaluate(async () => {
  const r = {};
  for (const nom of await caches.keys()) r[nom] = (await (await caches.open(nom)).keys()).length;
  return r;
});
await contexte.setOffline(true);
const rechargement = await page.reload({ waitUntil: "load" }).catch((e) => e);
const statut = rechargement?.status?.() ?? String(rechargement);
const contenu = await page.evaluate(() => ({ titre: document.title, appVide: document.querySelector("#app")?.childElementCount === 0 }));
await navigateur.close();
serveur.close();

const entrees = Object.values(caches).reduce((a, b) => a + b, 0);
console.log("caches :", JSON.stringify(caches));
console.log("hors ligne : statut", statut, JSON.stringify(contenu));
const propre = entrees > 0 && statut === 200 && !contenu.appVide;
console.log(propre ? "service worker : précache et rechargement hors ligne corrects" : "service worker : ÉCHEC");
process.exit(propre ? 0 : 1);
