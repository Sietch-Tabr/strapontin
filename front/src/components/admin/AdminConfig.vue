<template>
  <section class="admin-section" id="configuration">

    <EtatChargement v-if="chargement" />
    <template v-else>

    <!-- Général -->
    <div class="param-bloc">
      <h3 class="param-label">Général</h3>
      <template v-for="cle in ['CONTACT_EMAIL', 'PUBLIC_BASE_URL', 'SIGNAL_LIEN']" :key="cle">
        <div class="config-groupe-item">
          <div class="param-label-ligne">
            <span class="param-label">{{ cle }}</span>
            <span v-if="configParCle[cle]?.presente" class="badge badge-succes">Configurée</span>
            <span v-else class="badge badge-manquant" role="status">Non configurée</span>
          </div>
          <p class="param-aide">{{ DESCRIPTIONS_CONFIG[cle] }}</p>
          <p v-if="configParCle[cle]?.valeur" class="param-aide">{{ configParCle[cle].valeur }}</p>
          <form class="form-inline" @submit.prevent="sauvegarderConfig(cle, $event)">
            <input
              type="text"
              name="valeur"
              :value="configParCle[cle]?.valeur ?? ''"
              autocomplete="off"
              :aria-label="'Valeur de ' + cle"
            />
            <button type="submit" class="btn btn-secondary">Enregistrer</button>
          </form>
        </div>
      </template>
    </div>

    <!-- TM API -->
    <div class="param-bloc">
      <h3 class="param-label">TM API</h3>
      <template v-if="configParCle['TICKETMASTER_API_KEY']">
        <div class="param-label-ligne">
          <span class="param-label">TICKETMASTER_API_KEY</span>
          <span v-if="configParCle['TICKETMASTER_API_KEY'].presente" class="badge badge-succes">Configurée</span>
          <span v-else class="badge badge-manquant" role="status">Non configurée</span>
          <span v-if="etatTm === 'valide'" class="badge badge-succes">Valide</span>
          <span v-else-if="etatTm === 'invalide'" class="badge badge-erreur">Invalide</span>
        </div>
        <p class="param-aide">Clé d'API Ticketmaster Discovery v2.</p>
        <form class="form-inline" @submit.prevent="sauvegarderConfig('TICKETMASTER_API_KEY', $event)">
          <input type="password" name="valeur" autocomplete="new-password" placeholder="Nouvelle valeur…" aria-label="Valeur de TICKETMASTER_API_KEY" />
          <button type="submit" class="btn btn-secondary">Enregistrer</button>
        </form>
        <button
          v-if="configParCle['TICKETMASTER_API_KEY'].presente"
          type="button"
          class="btn btn-secondary"
          :disabled="testTmEnCours"
          @click="testerTm"
        >{{ testTmEnCours ? 'Test en cours…' : 'Tester la clé' }}</button>
      </template>
    </div>

    <!-- Configuration Resend -->
    <div class="param-bloc">
      <h3 class="param-label">Configuration Resend</h3>
      <template v-if="configParCle['RESEND_API_KEY']">
        <div class="param-label-ligne">
          <span class="param-label">RESEND_API_KEY</span>
          <span v-if="configParCle['RESEND_API_KEY'].presente" class="badge badge-succes">Configurée</span>
          <span v-else class="badge badge-manquant" role="status">Non configurée</span>
          <span v-if="etatEmail === 'envoye'" class="badge badge-succes">Email envoyé</span>
          <span v-else-if="etatEmail === 'erreur'" class="badge badge-erreur">Échec</span>
        </div>
        <p class="param-aide">Clé d'API Resend pour les notifications email.</p>
        <form class="form-inline" @submit.prevent="sauvegarderConfig('RESEND_API_KEY', $event)">
          <input type="password" name="valeur" autocomplete="new-password" placeholder="Nouvelle valeur…" aria-label="Valeur de RESEND_API_KEY" />
          <button type="submit" class="btn btn-secondary">Enregistrer</button>
        </form>
        <button
          v-if="configParCle['RESEND_API_KEY'].presente"
          type="button"
          class="btn btn-secondary"
          :disabled="testEmailEnCours"
          @click="testerEmail"
        >{{ testEmailEnCours ? 'Envoi en cours…' : 'Envoyer un email de test' }}</button>
      </template>
      <template v-if="configParCle['RESEND_WEBHOOK_SECRET']">
        <div class="config-groupe-item">
          <div class="param-label-ligne">
            <span class="param-label">RESEND_WEBHOOK_SECRET</span>
            <span v-if="configParCle['RESEND_WEBHOOK_SECRET'].presente" class="badge badge-succes">Configurée</span>
            <span v-else class="badge badge-manquant" role="status">Non configurée</span>
          </div>
          <p class="param-aide">{{ descriptionWebhookSecret }}</p>
          <form class="form-inline" @submit.prevent="sauvegarderConfig('RESEND_WEBHOOK_SECRET', $event)">
            <input type="password" name="valeur" autocomplete="new-password" placeholder="Nouvelle valeur…" aria-label="Valeur de RESEND_WEBHOOK_SECRET" />
            <button type="submit" class="btn btn-secondary">Enregistrer</button>
          </form>
        </div>
      </template>
    </div>

    <!-- VAPID -->
    <div class="param-bloc param-bloc-danger">
      <h3 class="param-label">VAPID</h3>
      <details class="config-vapid-details">
        <summary>Qu'est-ce que les clés VAPID ?</summary>
        <p class="param-aide">
          Les clés VAPID permettent d'authentifier le serveur auprès des services de
          notification push. Régénérer les clés invalide tous les abonnements existants.
          Les utilisateurs devront se réabonner.
        </p>
      </details>
      <template v-for="cle in ['VAPID_PUBLIC_KEY', 'VAPID_PRIVATE_KEY']" :key="cle">
        <div class="config-groupe-item">
          <div class="param-label-ligne">
            <span class="param-label">{{ cle }}</span>
            <span v-if="configParCle[cle]?.presente" class="badge badge-succes">Configurée</span>
            <span v-else class="badge badge-manquant" role="status">Non configurée</span>
          </div>
        </div>
      </template>
      <div class="config-groupe-item">
        <p class="param-aide config-vapid-avertissement">Régénérer les clés invalide tous les abonnements push existants.</p>
        <button type="button" class="btn btn-secondary" @click="ouvrirDialogVapid">
          Régénérer les clés VAPID (invalide tous les abonnements push)
        </button>
      </div>
    </div>

    <!-- APP_CONFIG_SECRET -->
    <div class="param-bloc">
      <h3 class="param-label">APP_CONFIG_SECRET</h3>
      <div class="param-label-ligne">
        <span v-if="appConfigSecretPresente" class="badge badge-succes" role="status">Clé configurée</span>
        <span v-else class="badge badge-manquant" role="status">Clé absente</span>
      </div>
      <p class="param-aide">Variable d'environnement de chiffrement de la configuration. Changer cette clé rend toutes les valeurs chiffrées illisibles.</p>
    </div>

    </template>
  </section>

  <dialog ref="dialogVapidRef" id="dialog-vapid">
    <p class="dialog-titre">Régénérer les clés VAPID ?</p>
    <p class="dialog-corps">
      Tous les abonnements push existants seront invalidés. Les utilisateurs devront se réabonner aux notifications.
    </p>
    <div class="dialog-pied">
      <button type="button" id="dialog-vapid-confirmer" class="btn btn-secondary" @click="confirmerVapid">Régénérer</button>
      <button type="button" id="dialog-vapid-annuler" class="btn btn-secondary" @click="fermerDialogVapid">Annuler</button>
    </div>
  </dialog>
</template>

<script setup>
defineOptions({ name: 'AdminConfig' })

import { shallowRef, ref, computed, onMounted } from 'vue'
import EtatChargement from '../EtatChargement.vue'
import { useDialog } from '../../composables/useDialog.js'
import { useToasts } from '../../composables/useToasts.js'

const { afficherToast } = useToasts()
const { dialogRef: dialogVapidRef, ouvrir: ouvrirDialogVapidModal, fermer: fermerDialogVapidModal } = useDialog()

const DESCRIPTIONS_CONFIG = {
  CONTACT_EMAIL:   'Adresse d\'expédition des emails (From: et Reply-To:). Doit appartenir à un domaine vérifié dans Resend.',
  PUBLIC_BASE_URL: 'URL publique de l\'application (ex. https://strapontin.kwaite.fr).',
  SIGNAL_LIEN:     'Lien Signal optionnel (groupe ou contact). Si renseigné, affiché sur la page Aide.',
}

const chargement = ref(true)
const configs = shallowRef([])
const appConfigSecretPresente = ref(false)
const etatTm = ref(null)
const testTmEnCours = ref(false)
const etatEmail = ref(null)
const testEmailEnCours = ref(false)

const configParCle = computed(() => {
  const map = {}
  for (const c of configs.value) map[c.cle] = c
  return map
})

const descriptionWebhookSecret = computed(() => {
  const baseUrl = configParCle.value['PUBLIC_BASE_URL']?.valeur ?? 'PUBLIC_BASE_URL'
  return (
    'Clé de signature des webhooks Resend (détection des bounces email).' +
    ' À récupérer dans le dashboard Resend → Webhooks → créer un endpoint' +
    ' avec l\'URL ' + baseUrl + '/api/webhooks/resend' +
    ' (événements : email.bounced, email.complained).'
  )
})

async function charger() {
  try {
    const [repConfig, repInfo] = await Promise.all([
      fetch('/api/admin/config'),
      fetch('/api/admin/info'),
    ])
    if (repConfig.ok) configs.value = await repConfig.json()
    if (repInfo.ok) {
      const info = await repInfo.json()
      appConfigSecretPresente.value = info.appConfigSecretPresente ?? false
    }
  } catch (_) {
    afficherToast('Erreur lors du chargement de la configuration.', 'erreur')
  } finally {
    chargement.value = false
  }
}

async function testerTm() {
  testTmEnCours.value = true
  etatTm.value = null
  try {
    const rep = await fetch('/api/admin/config/tester-tm', { method: 'POST' })
    etatTm.value = rep.ok ? 'valide' : 'invalide'
    if (!rep.ok) afficherToast('Clé Ticketmaster invalide.', 'erreur')
  } catch (_) {
    etatTm.value = 'invalide'
    afficherToast('Erreur réseau.', 'erreur')
  } finally {
    testTmEnCours.value = false
  }
}

async function testerEmail() {
  testEmailEnCours.value = true
  etatEmail.value = null
  try {
    const rep = await fetch('/api/admin/config/tester-email', { method: 'POST' })
    if (rep.ok) {
      etatEmail.value = 'envoye'
      afficherToast('Email de test envoyé.', 'succes')
    } else {
      etatEmail.value = 'erreur'
      const corps = await rep.json()
      afficherToast(corps.erreur || 'Envoi impossible.', 'erreur')
    }
  } catch (_) {
    etatEmail.value = 'erreur'
    afficherToast('Erreur réseau.', 'erreur')
  } finally {
    testEmailEnCours.value = false
  }
}

async function sauvegarderConfig(cle, event) {
  const valeur = event.target.querySelector('[name=valeur]')?.value ?? ''
  const rep = await fetch('/api/admin/config', {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ cle, valeur }),
  })
  const corps = await rep.json()
  if (rep.ok) {
    afficherToast('Configuration sauvegardée.', 'succes')
    if (cle === 'TICKETMASTER_API_KEY') etatTm.value = null
    if (cle === 'RESEND_API_KEY') etatEmail.value = null
    const repConfig = await fetch('/api/admin/config')
    if (repConfig.ok) configs.value = await repConfig.json()
  } else {
    afficherToast(corps.erreur || 'Erreur.', 'erreur')
  }
}

function ouvrirDialogVapid() {
  ouvrirDialogVapidModal()
}

function fermerDialogVapid() {
  fermerDialogVapidModal()
}

async function confirmerVapid() {
  fermerDialogVapid()
  const rep = await fetch('/api/admin/vapid/generer', { method: 'POST' })
  const corps = await rep.json()
  if (rep.ok) {
    afficherToast('Clés VAPID régénérées.', 'succes')
    const repConfig = await fetch('/api/admin/config')
    if (repConfig.ok) configs.value = await repConfig.json()
  } else {
    afficherToast(corps.erreur || 'Erreur lors de la génération.', 'erreur')
  }
}

onMounted(charger)
</script>
