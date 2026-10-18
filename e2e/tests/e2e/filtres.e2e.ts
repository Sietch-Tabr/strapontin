import { test, expect } from "@playwright/test";

// Barre de filtres de « Mes spectacles » et « Découverte » : repli sur mobile et tablette,
// recherche texte validée par son propre bouton.
const PAGES = [
  { chemin: "/spectacles", formulaire: "#filtres-spectacles" },
  { chemin: "/decouverte", formulaire: "#filtres-decouverte" },
];

for (const { chemin, formulaire } of PAGES) {
  test.describe(`Scénario: filtres ${chemin}`, () => {
    test(`filtres${chemin}_boutonFiltresSurMobile_replieEtDeplieLeFormulaire`, async ({
      page,
    }) => {
      await page.setViewportSize({ width: 390, height: 844 });
      await page.goto(chemin);
      // Un clic pendant le chargement initial peut tomber sur un rendu qui est ensuite refait.
      await page.waitForLoadState("networkidle");
      const bouton = page.getByRole("button", { name: /^Filtres/ });
      const form = page.locator(formulaire);

      await expect(bouton).toHaveAttribute("aria-expanded", "false");
      await expect(form).toBeHidden();

      await bouton.click();
      await expect(bouton).toHaveAttribute("aria-expanded", "true");
      await expect(form).toBeVisible();

      await bouton.click();
      await expect(bouton).toHaveAttribute("aria-expanded", "false");
      await expect(form).toBeHidden();
    });

    test(`filtres${chemin}_grandEcran_formulaireToujoursVisibleSansBouton`, async ({
      page,
    }) => {
      await page.setViewportSize({ width: 1280, height: 900 });
      await page.goto(chemin);

      await expect(page.locator(formulaire)).toBeVisible();
      await expect(page.getByRole("button", { name: /^Filtres/ })).toBeHidden();
    });

    test(`filtres${chemin}_rechercheTexte_valideeParLeBoutonRechercher`, async ({
      page,
    }) => {
      await page.setViewportSize({ width: 1280, height: 900 });
      await page.goto(chemin);
      const recherche = page.locator(`${formulaire} [role="search"]`);

      await expect(
        page.locator(formulaire).getByRole("button", { name: "Filtrer" }),
      ).toHaveCount(0);
      await recherche.getByRole("searchbox").fill("zzz-e2e");
      await recherche.getByRole("button", { name: "Rechercher" }).click();

      await expect(page).toHaveURL(/[?&]q=zzz-e2e/);
    });
  });
}
