import adapter from "@sveltejs/adapter-node";
import { fileURLToPath } from "url";
import { resolve, dirname } from "path";

const __dirname = dirname(fileURLToPath(import.meta.url));

/** @type {import('@sveltejs/kit').Config} */
const config = {
  compilerOptions: {
    runes: ({ filename }) =>
      filename.split(/[/\\]/).includes("node_modules") ? undefined : true,
  },
  kit: {
    csp: {
      mode: "nonce",
      directives: {
        "default-src": ["'self'"],
        "script-src": ["'self'"],
        "style-src": ["'self'", "'unsafe-inline'"],
        "img-src": ["'self'", "data:"],
        "worker-src": ["'self'"],
        "frame-ancestors": ["'none'"],
      },
    },
    adapter: adapter({
      // better-auth contient des annotations @__NO_SIDE_EFFECTS__ que Rollup
      // ne sait pas interpréter à certaines positions — on les ignore silencieusement.
      rollupOptions: {
        onwarn(warning, warn) {
          if (warning.code === "INVALID_ANNOTATION") return;
          warn(warning);
        },
      },
    }),
    env: {
      dir: resolve(__dirname, "../.."),
    },
  },
};

export default config;
