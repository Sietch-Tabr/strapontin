import tailwindcss from "@tailwindcss/vite";
import { sveltekit } from "@sveltejs/kit/vite";
import { defineConfig } from "vite";
import { resolve } from "path";

export default defineConfig({
  envDir: resolve(__dirname, "../.."),
  plugins: [tailwindcss(), sveltekit()],
});
