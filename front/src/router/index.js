import { createRouter, createWebHistory } from "vue-router";
import LoginPage from "../pages/auth/LoginPage.vue";
import TotpChallengePage from "../pages/auth/TotpChallengePage.vue";
import ForgotPasswordPage from "../pages/auth/ForgotPasswordPage.vue";
import ResetPasswordPage from "../pages/auth/ResetPasswordPage.vue";
import FirstLoginPage from "../pages/auth/FirstLoginPage.vue";

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
];

const router = createRouter({
  history: createWebHistory(),
  routes,
});

export default router;
