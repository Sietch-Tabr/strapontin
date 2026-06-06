<template>
  <section class="admin-section" id="banniere">
    <div class="param-bloc">
      <h3 class="param-label">Message système</h3>
      <p class="param-aide">
        Publiez un message visible par tous les utilisateurs connectés dans la minute.
        Le message réapparaît à chaque changement de page, sauf si l'utilisateur le ferme manuellement.
      </p>
      <div class="form-champ">
        <label for="banniere-message" class="form-label">Message</label>
        <textarea
          id="banniere-message"
          v-model="banniereMessage"
          name="message"
          class="form-control"
          rows="2"
          maxlength="500"
          placeholder="Maintenance planifiée ce soir à 22h…"
        ></textarea>
      </div>
      <div class="form-champ">
        <label for="banniere-type" class="form-label">Type</label>
        <select id="banniere-type" v-model="banniereType" name="type" class="form-control">
          <option value="info">Information (se cache après 10 s, réapparaît à la prochaine page)</option>
          <option value="warning">Avertissement (se cache après 20 s, réapparaît à la prochaine page)</option>
          <option value="error">Erreur (fermeture manuelle uniquement)</option>
        </select>
      </div>
      <div class="form-actions">
        <button type="button" class="btn btn-primary" @click="publierBanniere">Publier</button>
        <button type="button" class="btn btn-danger-outline" @click="effacerBanniere">Effacer</button>
      </div>
    </div>
  </section>
</template>

<style scoped>
.form-actions {
  display: flex;
  gap: 0.75rem;
  align-items: center;
  margin-top: 0.75rem;
}
</style>

<script setup>
defineOptions({ name: 'AdminBanniere' })

import { ref } from 'vue'
import { useToasts } from '../../composables/useToasts.js'

const { afficherToast } = useToasts()

const banniereMessage = ref('')
const banniereType = ref('info')

async function publierBanniere() {
  const msg = banniereMessage.value.trim()
  if (!msg) return
  const rep = await fetch('/api/admin/banniere', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ message: msg, type: banniereType.value }),
  })
  const corps = await rep.json()
  if (rep.ok) afficherToast('Bannière publiée.', 'succes')
  else afficherToast(corps.erreur || 'Erreur lors de la publication.', 'erreur')
}

async function effacerBanniere() {
  const rep = await fetch('/api/admin/banniere', { method: 'DELETE' })
  const corps = await rep.json()
  if (rep.ok) afficherToast('Bannière effacée.', 'succes')
  else afficherToast(corps.erreur || "Erreur lors de l'effacement.", 'erreur')
}
</script>
