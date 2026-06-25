import "./styles/base.css";
import "./styles/layout.css";
import "./styles/components.css";
import "./styles/pages.css";
import { createApp } from "vue";
import App from "./App.vue";
import router from "./router/index.js";
import { afficherToast } from "./composables/useToasts.js";

createApp(App).use(router).mount("#app");

// Exposition globale après le montage (environnement browser garanti),
// pour que le service worker (sw.js) puisse remonter ses erreurs.
window.afficherToast = afficherToast;

if ("serviceWorker" in navigator) {
  navigator.serviceWorker.register("/sw.js").catch(() => {});

  navigator.serviceWorker.addEventListener("message", (e) => {
    if (e.data?.type === "sw-error") {
      window.afficherToast?.(
        "Erreur service worker : " + e.data.message,
        "erreur",
      );
      return;
    }
    if (e.data?.type !== "nav-ancre") return;
    const cible = new URL(e.data.url, location.origin);
    if (location.pathname === cible.pathname && cible.hash) {
      document
        .getElementById(cible.hash.slice(1))
        ?.scrollIntoView({ behavior: "smooth" });
    } else {
      router.push(e.data.url);
    }
  });
}
