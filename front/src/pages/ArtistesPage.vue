<template>
  <div>
    <div class="page-header">
      <h1>Mes artistes</h1>
      <p v-if="donnees" class="page-subtitle">
        {{ donnees.artistes.length }} artiste{{ donnees.artistes.length !== 1 ? 's' : '' }} suivi{{ donnees.artistes.length !== 1 ? 's' : '' }}
      </p>
    </div>

    <form class="form-recherche" @submit.prevent="rechercher">
      <label for="q-artiste" class="sr-only">Rechercher un artiste</label>
      <input
        id="q-artiste"
        v-model="q"
        type="text"
        placeholder="Rechercher un artiste…"
        minlength="2"
        autocomplete="off"
      />
      <button type="submit" class="btn btn-secondary" :disabled="q.trim().length < 2">
        Rechercher
      </button>
    </form>
    <p class="form-aide">Saisissez au moins 2 caractères. La recherche propose les artistes déjà en base, puis des suggestions Deezer.</p>

    <div v-if="erreur" class="alerte alerte-erreur" role="alert" aria-live="assertive">{{ erreur }}</div>

    <template v-if="donnees">
      <section
        v-if="donnees.suggestions && donnees.suggestions.length > 0"
        class="section-resultats"
        aria-live="polite"
        aria-atomic="false"
      >
        <h2 class="section-label">Résultats pour « {{ qSoumis }} »</h2>
        <ul class="liste-cartes">
          <li v-for="s in donnees.suggestions" :key="s.nom" class="carte-item">
            <p class="carte-nom">{{ s.nom }}</p>
            <button
              type="button"
              class="btn btn-sm btn-secondary"
              :aria-label="'Suivre ' + s.nom"
              @click="ajouter(s.nom)"
            >Suivre</button>
          </li>
        </ul>
      </section>

      <div
        v-else-if="qSoumis && qSoumis.trim().length >= 2"
        class="section-resultats"
        aria-live="polite"
      >
        <div v-if="donnees.dejaLeSuivi" class="alerte alerte-info" role="status">
          Vous suivez déjà « {{ qSoumis }} ».
        </div>
        <div v-else class="alerte alerte-info" role="status">
          <p>Aucun résultat pour « {{ qSoumis }} ».</p>
          <button type="button" class="btn btn-primary" @click="ajouter(qSoumis)">
            Ajouter « {{ qSoumis }} » quand même
          </button>
        </div>
      </div>

      <div v-if="donnees.artistes.length === 0" class="etat-vide">
        <p class="etat-vide-titre">Aucun artiste suivi.</p>
        <p class="etat-vide-texte">Recherchez un artiste ci-dessus pour commencer.</p>
      </div>

      <section v-else>
        <h2 class="section-label">Artistes suivis</h2>
        <ul class="liste-cartes">
          <li v-for="artiste in donnees.artistes" :key="artiste.artisteNormalise" class="carte-item">
            <span class="carte-nom">{{ artiste.artisteAffiche }}</span>
            <button
              type="button"
              class="btn btn-sm btn-danger-texte"
              :aria-label="'Supprimer ' + artiste.artisteAffiche"
              @click="demanderSuppression(artiste)"
            >Supprimer</button>
          </li>
        </ul>
      </section>
    </template>

    <dialog
      ref="dialogRef"
      id="dialog-confirmer-suppr-artiste"
      aria-labelledby="titre-confirmer-suppr-artiste"
      aria-modal="true"
    >
      <div class="dialog-entete">
        <h2 id="titre-confirmer-suppr-artiste" class="dialog-titre">Supprimer l'artiste</h2>
      </div>
      <p>Arrêter de suivre « <strong>{{ artisteASupprimer?.artisteAffiche }}</strong> » ?</p>
      <div class="dialog-pied">
        <button type="button" class="btn btn-danger" @click="confirmerSuppression">Supprimer</button>
        <button type="button" class="btn btn-texte" @click="dialogRef.close()">Annuler</button>
      </div>
    </dialog>
  </div>
</template>

<script setup>
defineOptions({ name: 'ArtistesPage' })

import { ref, shallowRef, onMounted } from 'vue'
import { useDialog } from '../composables/useDialog.js'
import { useToasts } from '../composables/useToasts.js'

const { afficherToast } = useToasts()

const donnees = shallowRef(null)
const q = ref('')
const qSoumis = ref('')
const erreur = ref('')
const artisteASupprimer = ref(null)
const { dialogRef, ouvrir: ouvrirDialog, fermer: fermerDialog } = useDialog()

async function charger(recherche = '') {
  const params = recherche.trim().length >= 2 ? '?q=' + encodeURIComponent(recherche) : ''
  const rep = await fetch('/api/artistes' + params)
  if (!rep.ok) throw new Error()
  return rep.json()
}

async function rechercher() {
  erreur.value = ''
  try {
    qSoumis.value = q.value
    donnees.value = await charger(q.value)
  } catch {
    afficherToast('Impossible de rechercher.', 'erreur')
  }
}

async function ajouter(nom) {
  erreur.value = ''
  try {
    const rep = await fetch('/api/artistes', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ nom }),
    })
    if (rep.status === 409) {
      afficherToast('Vous suivez déjà cet artiste.', 'info')
      return
    }
    if (!rep.ok) {
      const data = await rep.json().catch(() => ({}))
      afficherToast(data.erreur || 'Erreur lors de l\'ajout.', 'erreur')
      return
    }
    afficherToast(`Fetch en cours pour « ${nom} »… Les concerts apparaîtront dans quelques secondes.`, 'info')
    q.value = ''
    qSoumis.value = ''
    donnees.value = await charger()
  } catch {
    afficherToast('Impossible d\'ajouter cet artiste.', 'erreur')
  }
}

function demanderSuppression(artiste) {
  artisteASupprimer.value = artiste
  ouvrirDialog()
}

async function confirmerSuppression() {
  fermerDialog()
  if (!artisteASupprimer.value) return
  try {
    const rep = await fetch('/api/artistes/' + encodeURIComponent(artisteASupprimer.value.artisteNormalise), {
      method: 'DELETE',
    })
    if (!rep.ok) throw new Error()
    afficherToast('Artiste retiré de votre liste.', 'succes')
    donnees.value = await charger()
  } catch {
    afficherToast('Impossible de supprimer cet artiste.', 'erreur')
  } finally {
    artisteASupprimer.value = null
  }
}

onMounted(async () => {
  try {
    donnees.value = await charger()
  } catch {
    afficherToast('Impossible de charger les artistes.', 'erreur')
  }
})
</script>
