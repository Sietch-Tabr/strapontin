// See https://svelte.dev/docs/kit/types#app.d.ts
// for information about these interfaces
declare global {
  namespace App {
    // interface Error {}
    interface Locals {
      theme: string;
      session: import("better-auth").Session | null;
      user:
        | (import("better-auth").User & {
            role: string;
            username?: string | null;
          })
        | null;
    }
    // interface PageData {}
    // interface PageState {}
    // interface Platform {}
  }
}

export {};
