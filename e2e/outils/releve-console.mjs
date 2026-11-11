// Relève la console du navigateur (error, warning), les erreurs de page et les réponses HTTP >= 400
// sur les pages indiquées, en session utilisateur puis administrateur, avec chromium, firefox et webkit.
//
// Usage (depuis e2e/, après un run E2E qui a créé les sessions .auth-*.json) :
//   node outils/releve-console.mjs                       pages par défaut
//   node outils/releve-console.mjs /decouverte /changelog     pages indiquées (les deux sessions)
// Variables : URL_DEV (défaut http://192.168.20.78:8080).
// Code de sortie 1 s'il reste un relevé inattendu.
//
// Relevé connu, compté à part et sans effet sur le code de sortie : WebKit signale une violation
// `style-src-attr` pour chaque <select> créé sous la politique de sécurité du projet
// (`style-src 'self'`) ; une page vide avec un seul <select> la produit aussi, Chromium jamais.
// Firefox avertit d'un champ mot de passe sur une page servie en HTTP (le dev, en HTTP sur IP
// privée) : seules les pages à champ mot de passe sont touchées, la production est en HTTPS.
import { chromium, firefox, webkit } from "@playwright/test";
import { fileURLToPath } from "node:url";

const base = process.env.URL_DEV ?? "http://192.168.20.78:8080";
const dossierAuth = fileURLToPath(new URL("../tests/e2e/", import.meta.url));
const pagesDemandees = process.argv.slice(2);

const sessions = {
  utilisateur: {
    etat: `${dossierAuth}.auth-user.json`,
    pages: pagesDemandees.length
      ? pagesDemandees
      : [
          "/",
          "/decouverte",
          "/spectacles",
          "/salles",
          "/artistes",
          "/changelog",
          "/parametres",
          "/aide",
        ],
  },
  admin: {
    etat: `${dossierAuth}.auth-admin.json`,
    pages: pagesDemandees.length
      ? pagesDemandees
      : [
          "/admin",
          "/admin#a-traiter",
          "/admin#referentiel",
          "/admin#sources",
          "/admin#logs",
          "/changelog",
          "/decouverte",
          "/spectacles",
        ],
  },
};
const moteurs = { chromium, firefox, webkit };

const estConnu = (moteur, texte) =>
  (moteur === "webkit" &&
    texte.includes("Refused to apply a stylesheet because its hash")) ||
  (moteur === "firefox" &&
    base.startsWith("http://") &&
    texte.includes("Password fields present on an insecure (http://) page"));

let inattendus = 0;
let connus = 0;
for (const [nomMoteur, moteur] of Object.entries(moteurs)) {
  const navigateur = await moteur.launch();
  for (const [nomSession, { etat, pages }] of Object.entries(sessions)) {
    const contexte = await navigateur.newContext({
      storageState: etat,
      viewport: { width: 1280, height: 900 },
    });
    for (const chemin of pages) {
      const page = await contexte.newPage();
      const releves = [];
      page.on("console", (m) => {
        if (["error", "warning"].includes(m.type()))
          releves.push(`console.${m.type()}: ${m.text().slice(0, 220)}`);
      });
      page.on("pageerror", (e) =>
        releves.push(`pageerror: ${e.message.slice(0, 220)}`),
      );
      page.on("response", (r) => {
        if (r.status() >= 400)
          releves.push(
            `HTTP ${r.status()} ${r.request().method()} ${r.url().replace(base, "")}`,
          );
      });
      await page.goto(base + chemin, { waitUntil: "load" });
      await page.waitForLoadState("networkidle").catch(() => {});
      await page.waitForTimeout(1500);
      const redirige =
        page.url().replace(base, "") !== chemin
          ? ` (redirigé vers ${page.url().replace(base, "")})`
          : "";
      const nouveaux = releves.filter((r) => !estConnu(nomMoteur, r));
      const connusPage = releves.length - nouveaux.length;
      connus += connusPage;
      inattendus += nouveaux.length;
      console.log(
        `[${nomMoteur}] ${nomSession} ${chemin}${redirige} : ${nouveaux.length} relevé(s)` +
          (connusPage ? ` (+${connusPage} connu(s))` : ""),
      );
      for (const r of nouveaux) console.log(`    ${r}`);
      await page.close();
    }
    await contexte.close();
  }
  await navigateur.close();
}
console.log(`TOTAL : ${inattendus} relevé(s) inattendu(s), ${connus} connu(s)`);
process.exit(inattendus === 0 ? 0 : 1);
