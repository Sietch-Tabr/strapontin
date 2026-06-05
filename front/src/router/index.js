import { createRouter, createWebHistory } from "vue-router";
import { sessionRef } from "../composables/useSession.js";
import LoginPage from "../pages/auth/LoginPage.vue";
import TotpChallengePage from "../pages/auth/TotpChallengePage.vue";
import ForgotPasswordPage from "../pages/auth/ForgotPasswordPage.vue";
import ResetPasswordPage from "../pages/auth/ResetPasswordPage.vue";
import FirstLoginPage from "../pages/auth/FirstLoginPage.vue";
import SetupPage from "../pages/auth/SetupPage.vue";
import AccueilPage from "../pages/AccueilPage.vue";
import ArtistesPage from "../pages/ArtistesPage.vue";
import SallesPage from "../pages/SallesPage.vue";
import SpectaclesPage from "../pages/SpectaclesPage.vue";
import DecouvertePage from "../pages/DecouvertePage.vue";
import AidePage from "../pages/AidePage.vue";
import ChangelogPage from "../pages/ChangelogPage.vue";
import ParametresPage from "../pages/ParametresPage.vue";
import ParametresTotpPage from "../pages/ParametresTotpPage.vue";
import AdminPage from "../pages/AdminPage.vue";

const routes = [
  { path: "/login", component: LoginPage, meta: { authPage: true } },
  {
    path: "/auth/totp",
    component: TotpChallengePage,
    meta: { authPage: true },
  },
  {
    path: "/forgot-password",
    component: ForgotPasswordPage,
    meta: { authPage: true },
  },
  {
    path: "/reset-password",
    component: ResetPasswordPage,
    meta: { authPage: true },
  },
  { path: "/first-login", component: FirstLoginPage, meta: { authPage: true } },
  { path: "/setup", component: SetupPage, meta: { authPage: true } },
  { path: "/", component: AccueilPage },
  { path: "/artistes", component: ArtistesPage },
  { path: "/salles", component: SallesPage },
  { path: "/spectacles", component: SpectaclesPage },
  { path: "/decouverte", component: DecouvertePage },
  { path: "/aide", component: AidePage },
  { path: "/changelog", component: ChangelogPage },
  { path: "/parametres", component: ParametresPage },
  { path: "/parametres/totp", component: ParametresTotpPage },
  { path: "/admin", component: AdminPage },
];

const router = createRouter({
  history: createWebHistory(),
  routes,
});

router.beforeEach((to) => {
  if (to.meta.authPage && sessionRef.value) return "/";
});

export default router;
