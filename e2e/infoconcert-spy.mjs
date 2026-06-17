import { chromium } from '@playwright/test';
const nav = await chromium.launch({ headless: true });
const page = await nav.newPage();

await page.goto('https://web.digitick.com/index-css5-lempreinte-pg1.html', {
  waitUntil: 'networkidle', timeout: 25000
}).catch(() => {});

const concerts = await page.evaluate(() => {
  const items = [];
  document.querySelectorAll('[class*="evenement"][class*="evenement"]:not([class*="Nom"]):not([class*="Genre"]):not([class*="Date"]):not([class*="Salle"]):not([class*="Tarif"]):not([class*="Reserver"]):not([class*="Jour"]):not([class*="Heure"])').forEach(el => {
    if (!el.className.match(/^evenement\d/)) return;
    const nom = el.querySelector('.evenementNom')?.innerText?.trim();
    const date = el.querySelector('.evenementJour')?.innerText?.trim().replace(/\s+/g, ' ');
    const genre = el.querySelector('.evenementGenre')?.innerText?.trim();
    const tarif = el.querySelector('.evenementTarifsChoix')?.innerText?.trim();
    const href = el.querySelector('.evenementNom')?.href || el.querySelector('.evenementReserver')?.href;
    const idMatch = href?.match(/ei(\d+)/);
    if (nom) items.push({ nom, date, genre, tarif, id: idMatch?.[1], href });
  });
  return items;
});

console.log(`${concerts.length} concerts trouvés sur web.digitick.com pour L'Empreinte:\n`);
concerts.forEach(c => console.log(`[${c.id}] ${c.nom} | ${c.date} | ${c.genre} | ${c.tarif}`));

await nav.close();
