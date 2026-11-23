import { type Page } from "@playwright/test";

// Ouvre une section d'une page à onglets : le sélecteur sur petit écran, le lien d'onglet sinon.
// Les deux commandes n'existent pas ensemble selon la largeur, et elles n'apparaissent
// qu'une fois la page rendue : isVisible() ne patiente pas et choisirait à tort le lien sur
// mobile. On attend donc que l'une des deux soit affichée avant de décider.
export async function ouvrirSection(page: Page, identifiant: string) {
  const selecteur = page.locator("select.param-onglets-mobile");
  const onglet = page.locator(`a.param-onglet[href="#${identifiant}"]`);
  await page
    .locator(
      `select.param-onglets-mobile:visible, a.param-onglet[href="#${identifiant}"]:visible`,
    )
    .first()
    .waitFor({ state: "visible" });
  if (await selecteur.isVisible()) {
    await selecteur.selectOption(`#${identifiant}`);
  } else {
    await onglet.click();
  }
}
