<template>
  <div>
    <div class="page-header">
      <h1>Administration</h1>
    </div>

    <select
      class="param-onglets-mobile"
      :value="ongletActif"
      aria-label="Sections de l'administration"
      @change="naviguerVers($event.target.value)"
    >
      <option v-for="onglet in onglets" :key="onglet.ancre" :value="onglet.ancre">{{ onglet.label }}</option>
    </select>

    <nav class="param-onglets" aria-label="Sections de l'administration">
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

    <div class="admin-sections">
      <KeepAlive><AdminJobs        v-if="ongletActif === '#jobs'"          /></KeepAlive>
      <KeepAlive><AdminLogs        v-if="ongletActif === '#logs'"          /></KeepAlive>
      <KeepAlive><AdminConfig      v-if="ongletActif === '#configuration'" /></KeepAlive>
      <KeepAlive><AdminBanniere    v-if="ongletActif === '#banniere'"      /></KeepAlive>
      <KeepAlive><AdminUtilisateurs v-if="ongletActif === '#utilisateurs'" /></KeepAlive>
    </div>
  </div>
</template>

<script setup>
defineOptions({ name: 'AdminPage' })

import { ref, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import AdminJobs         from '../components/admin/AdminJobs.vue'
import AdminLogs         from '../components/admin/AdminLogs.vue'
import AdminConfig       from '../components/admin/AdminConfig.vue'
import AdminBanniere     from '../components/admin/AdminBanniere.vue'
import AdminUtilisateurs from '../components/admin/AdminUtilisateurs.vue'

const route = useRoute()
const router = useRouter()

const onglets = [
  { ancre: '#jobs',          label: 'Tableau de bord' },
  { ancre: '#logs',          label: 'Logs' },
  { ancre: '#configuration', label: 'Configuration' },
  { ancre: '#banniere',      label: 'Bannière' },
  { ancre: '#utilisateurs',  label: 'Utilisateurs' },
]
const ongletActif = ref('#jobs')

function naviguerVers(ancre) {
  ongletActif.value = ancre
  router.replace({ hash: ancre })
}

onMounted(() => {
  const hash = route.hash || '#jobs'
  ongletActif.value = onglets.some((o) => o.ancre === hash) ? hash : '#jobs'
})
</script>
