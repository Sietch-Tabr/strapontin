// See https://svelte.dev/docs/kit/types#app.d.ts
// for information about these interfaces
declare global {
  namespace App {
    // interface Error {}
    interface Locals {
      theme: "auto" | "clair" | "sombre";
      user: {
        id: string;
        identifiant: string;
        nom: string;
        email: string;
        role: string;
        mustChangeCredentials: boolean;
      } | null;
    }
    // interface PageData {}
    // interface PageState {}
    // interface Platform {}
  }
}

export {};
