<template>
  <div>
    <div class="page-entete">
      <h1>Paramètres</h1>
    </div>

    <select
      v-if="estMobile"
      v-model="ongletActif"
      class="param-onglets-mobile"
      aria-label="Sections des paramètres"
    >
      <option v-for="onglet in onglets" :key="onglet.ancre" :value="onglet.ancre">{{ onglet.label }}</option>
    </select>

    <nav v-else class="param-onglets" aria-label="Sections des paramètres">
      <a
        v-for="onglet in onglets"
        :key="onglet.ancre"
        :href="onglet.ancre"
        class="param-onglet"
        :class="{ actif: ongletActif === onglet.ancre }"
        :aria-current="ongletActif === onglet.ancre ? 'true' : undefined"
        @click.prevent="naviguerVers(onglet.ancre)"
      >{{ onglet.label }}</a>
    </nav>

    <div class="parametres-sections">
      <KeepAlive><ParametresPreferences   v-if="ongletActif === '#preferences'"  /></KeepAlive>
      <KeepAlive><ParametresNotifications v-if="ongletActif === '#notifications'" /></KeepAlive>
      <KeepAlive><ParametresCompte        v-if="ongletActif === '#compte'"        /></KeepAlive>
      <KeepAlive><ParametresDonnees       v-if="ongletActif === '#donnees'"       /></KeepAlive>
    </div>
  </div>
</template>

<script setup>
defineOptions({ name: 'ParametresPage' })

import { ref, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useToasts } from '../composables/useToasts.js'
import { useEstMobile } from '../composables/useBreakpoint.js'

const estMobile = useEstMobile()
import ParametresPreferences   from '../components/parametres/ParametresPreferences.vue'
import ParametresNotifications from '../components/parametres/ParametresNotifications.vue'
import ParametresCompte        from '../components/parametres/ParametresCompte.vue'
import ParametresDonnees       from '../components/parametres/ParametresDonnees.vue'

const route = useRoute()
const router = useRouter()
const { afficherToast } = useToasts()

const onglets = [
  { ancre: '#preferences',  label: 'Préférences' },
  { ancre: '#notifications', label: 'Notifications' },
  { ancre: '#compte',        label: 'Compte' },
  { ancre: '#donnees',       label: 'Données' },
]
const ongletActif = ref('#preferences')

function naviguerVers(ancre) {
  ongletActif.value = ancre
  router.replace({ hash: ancre })
}

function traiterSuccesUrl() {
  const succes = route.query.succes
  const erreur = route.query.erreur
  if (succes === 'email-confirme') {
    afficherToast('Adresse email mise à jour.', 'succes')
    ongletActif.value = '#compte'
  } else if (erreur === 'lien-expire') {
    afficherToast('Lien expiré ou invalide. Recommencez depuis Paramètres.', 'erreur')
    ongletActif.value = '#compte'
  } else if (erreur === 'lien-invalide') {
    afficherToast('Lien invalide.', 'erreur')
    ongletActif.value = '#compte'
  } else if (erreur === 'email-deja-utilise') {
    afficherToast('Cette adresse email est désormais utilisée par un autre compte.', 'erreur')
    ongletActif.value = '#compte'
  }
  if (succes || erreur) {
    router.replace({ query: {}, hash: ongletActif.value })
  }
}

onMounted(() => {
  const hash = route.hash || '#preferences'
  ongletActif.value = onglets.some((o) => o.ancre === hash) ? hash : '#preferences'
  traiterSuccesUrl()
})
</script>
