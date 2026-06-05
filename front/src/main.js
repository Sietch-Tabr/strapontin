import { createApp } from "vue";
import App from "./App.vue";
import router from "./router/index.js";

createApp(App).use(router).mount("#app");

if ("serviceWorker" in navigator) {
  navigator.serviceWorker.register("/sw.js").catch(() => {});

  navigator.serviceWorker.addEventListener("message", (e) => {
    if (e.data?.type === "sw-error") {
      window.afficherToast?.("Erreur service worker : " + e.data.message, "erreur");
      return;
    }
    if (e.data?.type !== "nav-ancre") return;
    const cible = new URL(e.data.url, location.origin);
    if (location.pathname === cible.pathname && cible.hash) {
      document.getElementById(cible.hash.slice(1))?.scrollIntoView({ behavior: "smooth" });
    } else {
      router.push(e.data.url);
    }
  });
}
