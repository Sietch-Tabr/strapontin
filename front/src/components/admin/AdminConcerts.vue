<template>
  <section class="admin-section" id="concerts">
    <div class="param-bloc">
      <h3 class="param-label">Concerts à confirmer</h3>
      <p class="etat-vide-texte" v-if="!chargement && !concerts.length">
        Aucun concert flagué pour suppression pour l'instant.
      </p>
      <EtatChargement v-if="chargement" />
      <div v-else-if="concerts.length" class="table-wrapper">
        <table id="table-concerts-a-confirmer">
          <thead>
            <tr>
              <th>Artiste</th>
              <th>Salle</th>
              <th>Ville</th>
              <th>Date</th>
              <th>Absences</th>
              <th>Flagué le</th>
              <th>Actions</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="concert in concerts" :key="concert.id">
              <td data-label="Artiste">{{ concert.artiste }}</td>
              <td data-label="Salle">{{ concert.salle }}</td>
              <td data-label="Ville">{{ concert.ville }}</td>
              <td data-label="Date">{{ formaterAvecHeure(concert.date, concert.heure) }}</td>
              <td data-label="Absences">{{ concert.absencesConsecutives }}</td>
              <td data-label="Flagué le">{{ formaterInstant(concert.suppressionSuggereeLe) }}</td>
              <td data-label="Actions" class="source-actions">
                <button type="button" class="btn btn-secondary btn-sm" @click="annuler(concert)">
                  Annuler (garder)
                </button>
                <button
                  type="button"
                  class="btn btn-danger btn-sm"
                  @click="ouvrirConfirmation(concert)"
                >Supprimer</button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>

      <div class="pagination" v-if="totalPages > 1">
        <button type="button" class="btn btn-secondary btn-sm" :disabled="page === 0" @click="allerPage(page - 1)">← Précédent</button>
        <span>Page {{ page + 1 }} / {{ totalPages }} ({{ total }} concerts)</span>
        <button type="button" class="btn btn-secondary btn-sm" :disabled="page >= totalPages - 1" @click="allerPage(page + 1)">Suivant →</button>
      </div>
    </div>
  </section>

  <dialog ref="dialogSuppressionRef" aria-labelledby="titre-suppression-concert" aria-modal="true">
    <div class="dialog-entete">
      <h2 id="titre-suppression-concert" class="dialog-titre">Supprimer définitivement</h2>
    </div>
    <p v-if="concertASupprimer">
      Supprimer « {{ concertASupprimer.artiste }} » à « {{ concertASupprimer.salle }} » du
      {{ formaterAvecHeure(concertASupprimer.date, concertASupprimer.heure) }} ? Action
      irréversible.
    </p>
    <div class="dialog-pied">
      <button
        type="button"
        class="btn btn-danger"
        :disabled="enCoursSuppression"
        @click="confirmerSuppression"
      >Supprimer</button>
      <button type="button" class="btn btn-texte" @click="fermerConfirmation">Annuler</button>
    </div>
  </dialog>
</template>

<script setup>
defineOptions({ name: 'AdminConcerts' })

import { ref, shallowRef, onMounted, onUnmounted } from 'vue'
import { useToasts } from '../../composables/useToasts.js'
import { useDialog } from '../../composables/useDialog.js'
import { formaterAvecHeure, formaterInstant } from '../../composables/useDates.js'
import EtatChargement from '../EtatChargement.vue'

const { afficherToast } = useToasts()

const chargement = ref(true)
const concerts = shallowRef([])
const page = ref(0)
const totalPages = ref(1)
const total = ref(0)
const controleur = new AbortController()

const { dialogRef: dialogSuppressionRef, ouvrir: ouvrirDialogSuppression, fermer: fermerConfirmation } =
  useDialog()
const concertASupprimer = ref(null)
const enCoursSuppression = ref(false)

async function charger() {
  chargement.value = true
  try {
    const rep = await fetch(`/api/admin/concerts-a-confirmer?page=${page.value}`, { signal: controleur.signal })
    if (rep.ok) {
      const data = await rep.json()
      concerts.value = data.concerts ?? []
      page.value = data.page ?? 0
      totalPages.value = data.totalPages ?? 1
      total.value = data.total ?? 0
    } else {
      afficherToast('Erreur lors du chargement des concerts à confirmer.', 'erreur')
    }
  } catch (e) {
    if (e.name !== 'AbortError') {
      afficherToast('Erreur réseau lors du chargement des concerts à confirmer.', 'erreur')
    }
  } finally {
    chargement.value = false
  }
}

function allerPage(n) {
  page.value = Math.max(0, Math.min(n, totalPages.value - 1))
  charger()
}

async function annuler(concert) {
  try {
    const rep = await fetch(`/api/admin/concerts-a-confirmer/${concert.id}/annuler`, {
      method: 'POST',
      signal: controleur.signal,
    })
    if (rep.ok) {
      afficherToast('Concert conservé, compteur d\'absences réinitialisé.', 'succes')
      await charger()
    } else {
      afficherToast('Erreur lors de l\'annulation.', 'erreur')
    }
  } catch (e) {
    if (e.name !== 'AbortError') afficherToast('Erreur réseau.', 'erreur')
  }
}

function ouvrirConfirmation(concert) {
  concertASupprimer.value = concert
  ouvrirDialogSuppression()
}

async function confirmerSuppression() {
  if (!concertASupprimer.value || enCoursSuppression.value) return
  enCoursSuppression.value = true
  try {
    const rep = await fetch(
      `/api/admin/concerts-a-confirmer/${concertASupprimer.value.id}/confirmer`,
      { method: 'POST', signal: controleur.signal },
    )
    if (rep.ok) {
      fermerConfirmation()
      afficherToast('Concert supprimé.', 'succes')
      await charger()
    } else {
      afficherToast('Erreur lors de la suppression.', 'erreur')
    }
  } catch (e) {
    if (e.name !== 'AbortError') afficherToast('Erreur réseau.', 'erreur')
  } finally {
    enCoursSuppression.value = false
  }
}

onMounted(charger)
onUnmounted(() => controleur.abort())
</script>
