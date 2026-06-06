<template>
  <section class="admin-section" id="banniere">
    <div class="param-bloc">
      <h3 class="param-label">Message système</h3>
      <p class="param-aide">
        Publiez un message visible par tous les utilisateurs connectés dans la minute.
        Le message disparaît automatiquement selon son type ou manuellement sur fermeture.
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
          <option value="info">Information (auto-dismiss 10 s)</option>
          <option value="warning">Avertissement (auto-dismiss 20 s)</option>
          <option value="error">Erreur (fermeture manuelle)</option>
        </select>
      </div>
      <div class="form-actions">
        <button type="button" class="btn btn-primary" @click="publierBanniere">Publier</button>
        <button type="button" class="btn btn-secondary" @click="effacerBanniere">Effacer</button>
      </div>
    </div>
  </section>
</template>

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
