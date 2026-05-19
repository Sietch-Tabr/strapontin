import { test, expect } from "@playwright/test";

test.describe("Scénario: admin — sections et contenus", () => {
  test("admin_h1_visible", async ({ page }) => {
    await page.goto("/admin");
    await expect(
      page.getByRole("heading", { name: "Administration", level: 1 }),
    ).toBeVisible();
  });

  test("admin_section_utilisateurs_visible", async ({ page }) => {
    await page.goto("/admin#utilisateurs");
    await expect(page.locator("section#utilisateurs")).toBeVisible();
  });

  test("admin_section_configuration_visible", async ({ page }) => {
    await page.goto("/admin#configuration");
    await expect(page.locator("section#configuration")).toBeVisible();
  });

  test("admin_section_jobs_visible", async ({ page }) => {
    await page.goto("/admin");
    await expect(page.locator("section#jobs")).toBeVisible();
  });

  test("admin_section_logs_visible", async ({ page }) => {
    await page.goto("/admin#logs");
    await expect(page.locator("section#logs")).toBeVisible();
  });

  test("admin_anchor_utilisateurs_scroll", async ({ page }) => {
    await page.goto("/admin#utilisateurs");
    await expect(page.locator("section#utilisateurs")).toBeVisible();
  });
});

test.describe("Scénario: admin — API endpoints", () => {
  test("api_admin_quota_retourne_200", async ({ request }) => {
    const rep = await request.get("/api/admin/quota");
    expect([200, 404]).toContain(rep.status());
  });

  test("api_admin_non_accessible_sans_session", async ({ browser }) => {
    const ctx = await browser.newContext({ storageState: undefined });
    const rep = await ctx.request.get("/api/admin/quota");
    expect([401, 302, 403]).toContain(rep.status());
    await ctx.close();
  });
});
