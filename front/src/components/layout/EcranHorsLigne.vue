<template>
  <section
    class="ecran-hors-ligne"
    role="status"
    aria-labelledby="titre-hors-ligne"
  >
    <h1 id="titre-hors-ligne" class="ecran-hors-ligne__titre">
      Vous êtes hors ligne
    </h1>
    <p class="ecran-hors-ligne__texte texte-secondaire">
      Aucune donnée n'est encore gardée sur cet appareil. Strapontin se
      rechargera de lui-même dès que la connexion reviendra.
    </p>
    <button type="button" class="bouton bouton-secondaire" @click="recharger">
      Réessayer
    </button>
  </section>
</template>

<script setup>
defineOptions({ name: "EcranHorsLigne" });

function recharger() {
  window.location.reload();
}
</script>

<style scoped>
.ecran-hors-ligne {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: var(--espace-8);
  padding: var(--espace-16);
  text-align: center;
}

.ecran-hors-ligne__titre {
  font-size: var(--texte-xl);
}

.ecran-hors-ligne__texte {
  max-width: 24rem;
}
</style>
