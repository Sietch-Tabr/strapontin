import {
  request as playwrightRequest,
  expect,
  type APIRequestContext,
  type Page,
} from "@playwright/test";
import { FICHIER_AUTH_ADMIN } from "../global-setup.js";

// #949 : le pattern `playwrightRequest.newContext({ baseURL, storageState: FICHIER_AUTH_ADMIN })`
// était dupliqué une dizaine de fois entre admin.e2e.ts et admin-referentiel.e2e.ts.

export const BASE_URL =
  process.env["E2E_BASE_URL"] ?? "http://192.168.20.78:8080";

export async function creerContexteAdmin(): Promise<APIRequestContext> {
  return playwrightRequest.newContext({
    baseURL: BASE_URL,
    storageState: FICHIER_AUTH_ADMIN,
  });
}

export async function supprimerUtilisateurParEmail(
  email: string,
): Promise<void> {
  const ctx = await creerContexteAdmin();
  try {
    const reps = await ctx.get("/api/admin/utilisateurs");
    if (!reps.ok()) return;
    const users = await reps.json();
    const cible = users.find((u: { email: string }) => u.email === email);
    if (cible) await ctx.delete(`/api/admin/utilisateurs/${cible.id}`);
  } finally {
    await ctx.dispose();
  }
}

export async function naviguerVersUtilisateurs(
  page: Page,
  isMobile: boolean,
): Promise<void> {
  if (isMobile) {
    await page.selectOption("select.param-onglets-mobile", "#utilisateurs");
  } else {
    await page.locator('a[href="#utilisateurs"]').click();
  }
  await expect(page.locator("section#utilisateurs")).toBeVisible();
}
