<template>
  <div>
    <div class="page-entete">
      <h1>Administration</h1>
    </div>

    <select
      v-if="estMobile"
      v-model="ongletActif"
      class="param-onglets-mobile"
      aria-label="Sections de l'administration"
    >
      <option v-for="onglet in onglets" :key="onglet.ancre" :value="onglet.ancre">{{ onglet.label }}</option>
    </select>

    <nav v-else class="param-onglets" aria-label="Sections de l'administration">
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
      <KeepAlive><AdminJobs         v-if="ongletActif === '#jobs'"          /></KeepAlive>
      <KeepAlive><AdminSources      v-if="ongletActif === '#sources'"       /></KeepAlive>
      <KeepAlive><AdminSalles       v-if="ongletActif === '#salles'"        /></KeepAlive>
      <KeepAlive><AdminArtistes     v-if="ongletActif === '#artistes'"      /></KeepAlive>
      <KeepAlive><AdminConcerts     v-if="ongletActif === '#concerts'"      /></KeepAlive>
      <KeepAlive><AdminLogs         v-if="ongletActif === '#logs'"          /></KeepAlive>
      <KeepAlive><AdminConfig       v-if="ongletActif === '#configuration'" /></KeepAlive>
      <KeepAlive><AdminBanniere     v-if="ongletActif === '#banniere'"      /></KeepAlive>
      <KeepAlive><AdminUtilisateurs v-if="ongletActif === '#utilisateurs'"  /></KeepAlive>
    </div>
  </div>
</template>

<script setup>
defineOptions({ name: 'AdminPage' })

import { ref, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useEstMobile } from '../composables/useBreakpoint.js'
import AdminJobs          from '../components/admin/AdminJobs.vue'
import AdminSources       from '../components/admin/AdminSources.vue'
import AdminSalles        from '../components/admin/AdminSalles.vue'
import AdminArtistes      from '../components/admin/AdminArtistes.vue'
import AdminConcerts      from '../components/admin/AdminConcerts.vue'
import AdminLogs          from '../components/admin/AdminLogs.vue'
import AdminConfig        from '../components/admin/AdminConfig.vue'
import AdminBanniere      from '../components/admin/AdminBanniere.vue'
import AdminUtilisateurs  from '../components/admin/AdminUtilisateurs.vue'

const route = useRoute()
const router = useRouter()
const estMobile = useEstMobile()

const onglets = [
  { ancre: '#jobs',          label: 'Tableau de bord' },
  { ancre: '#sources',       label: 'Sources' },
  { ancre: '#salles',        label: 'Salles' },
  { ancre: '#artistes',      label: 'Artistes' },
  { ancre: '#concerts',      label: 'Concerts' },
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

<style scoped>
  .admin-sections {
    max-width: 56rem;
  }
</style>
