import { createRouter, createWebHistory } from "vue-router";
import {
  sessionRef,
  sessionInitialisee,
  sessionErreurReseau,
} from "../composables/useSession.js";
import { scrollBehavior } from "../utils/scrollBehavior.js";
import { redirectionAncreAdmin } from "../utils/ancresAdmin.js";
import { repliSansDroitAdmin } from "../utils/gardeAdmin.js";
import LoginPage from "../pages/auth/LoginPage.vue";
import TotpChallengePage from "../pages/auth/TotpChallengePage.vue";
import ForgotPasswordPage from "../pages/auth/ForgotPasswordPage.vue";
import ResetPasswordPage from "../pages/auth/ResetPasswordPage.vue";
import SetupPage from "../pages/auth/SetupPage.vue";

const AccueilPage = () => import("../pages/AccueilPage.vue");
const ArtistesPage = () => import("../pages/ArtistesPage.vue");
const SallesPage = () => import("../pages/SallesPage.vue");
const SpectaclesPage = () => import("../pages/SpectaclesPage.vue");
const DecouvertePage = () => import("../pages/DecouvertePage.vue");
const AidePage = () => import("../pages/AidePage.vue");
const ChangelogPage = () => import("../pages/ChangelogPage.vue");
const ParametresPage = () => import("../pages/ParametresPage.vue");
const ParametresTotpPage = () => import("../pages/ParametresTotpPage.vue");
const AdminPage = () => import("../pages/AdminPage.vue");

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
  { path: "/admin", component: AdminPage, meta: { admin: true } },
];

const router = createRouter({
  history: createWebHistory(),
  routes,
  scrollBehavior,
});

router.beforeEach((to) => {
  if (to.meta.authPage && sessionRef.value) return "/";
  // ne redirige vers /login que sur un 401 confirmé — pas quand la session n'a jamais pu
  // être vérifiée faute de réseau (redémarrage PWA hors-ligne depuis le cache SW), où le cookie
  // de session reste potentiellement valide.
  if (
    !to.meta.authPage &&
    sessionInitialisee.value &&
    !sessionRef.value &&
    !sessionErreurReseau.value
  )
    return "/login";
  const repli = repliSansDroitAdmin(to, sessionRef.value);
  if (repli) return repli;
  // Dans la navigation elle-même, avant le chargement de la page : l'onglet visé est
  // déterminé dès l'arrivée, sans seconde navigation.
  return redirectionAncreAdmin(to) ?? true;
});

export default router;
