<template>
  <section class="parametres-section" id="notifications">
    <h2 class="lecteur-ecran">Notifications</h2>

    <EtatChargement v-if="chargement" />
    <template v-else>

    <div class="param-bloc">
      <h3 class="param-libelle">Notifications email</h3>
      <form class="form-vertical" @submit.prevent="sauvegarderNotifications">
        <fieldset class="form-checkboxes" :disabled="!resendDisponible">
          <legend class="form-label">Fréquence des emails</legend>
          <label class="notif-radio">
            <input v-model="frequenceNotifications" type="radio" value="jamais" />
            <span>Jamais</span>
          </label>
          <label class="notif-radio">
            <input v-model="frequenceNotifications" type="radio" value="quotidien" />
            <span>Récapitulatif quotidien (8h)</span>
          </label>
          <label class="notif-radio">
            <input v-model="frequenceNotifications" type="radio" value="temps_reel" />
            <span>À chaque nouveau concert</span>
          </label>
          <p v-if="!resendDisponible" class="form-aide">
            Les emails ne sont pas disponibles (clé Resend non configurée).
          </p>
        </fieldset>

        <fieldset class="notif-grille">
          <legend class="form-label">Concerts à notifier</legend>
          <div class="notif-grille__entete" aria-hidden="true">
            <span></span>
            <span>Email</span>
            <span>Push</span>
          </div>
          <div class="notif-grille__ligne">
            <span class="notif-grille__libelle">Artistes suivis</span>
            <label class="notif-grille__case">
              <span class="lecteur-ecran">Email — artistes suivis</span>
              <input
                v-model="notifArtistes"
                type="checkbox"
                :disabled="!resendDisponible"
              />
            </label>
            <label class="notif-grille__case">
              <span class="lecteur-ecran">Push — artistes suivis</span>
              <input v-model="notifArtistesPush" type="checkbox" />
            </label>
          </div>
          <div class="notif-grille__ligne">
            <span class="notif-grille__libelle">Salles suivies</span>
            <label class="notif-grille__case">
              <span class="lecteur-ecran">Email — salles suivies</span>
              <input
                v-model="notifSalles"
                type="checkbox"
                :disabled="!resendDisponible"
              />
            </label>
            <label class="notif-grille__case">
              <span class="lecteur-ecran">Push — salles suivies</span>
              <input v-model="notifSallesPush" type="checkbox" />
            </label>
          </div>
        </fieldset>

        <button type="submit" class="bouton bouton-secondaire">Enregistrer</button>
      </form>
    </div>

    <div v-if="pushSupporte" class="param-bloc param-bloc-push">
      <h3 class="param-libelle">Notifications push</h3>
      <div v-if="!vapidDisponible">
        <p class="param-aide">
          Les notifications push ne sont pas configurées (clé VAPID manquante dans la
          configuration admin).
        </p>
      </div>
      <div v-else-if="pushPermission === 'denied'">
        <p class="param-aide">
          Les notifications sont bloquées dans votre navigateur. Pour les activer,
          modifiez les permissions du site dans les paramètres de votre navigateur.
        </p>
      </div>
      <div v-else-if="pushAbonne">
        <p class="param-aide">
          Les notifications push sont activées sur cet appareil.
        </p>
        <div class="param-actions">
          <button
            type="button"
            class="bouton bouton-secondaire bouton-petit"
            :disabled="testEnCours"
            @click="testerPush"
          >
            Envoyer une notification de test
          </button>
          <button
            type="button"
            class="bouton bouton-secondaire"
            :disabled="pushEnCours"
            @click="desabonnerPush"
          >
            Désactiver les notifications push
          </button>
        </div>
      </div>
      <div v-else>
        <p class="param-aide">
          Recevez une notification sur cet appareil dès qu'un nouveau concert correspond à vos
          préférences.
        </p>
        <p v-if="frequenceNotifications === 'jamais'" class="param-aide">
          Activez d'abord une fréquence de notification ci-dessus — sur "Jamais", aucune
          notification push ne serait envoyée.
        </p>
        <button
          type="button"
          class="bouton bouton-secondaire"
          :disabled="pushEnCours || frequenceNotifications === 'jamais'"
          @click="abonnerPush"
        >
          Activer les notifications push
        </button>
      </div>
    </div>

    </template>
  </section>
</template>

<script setup>
defineOptions({ name: 'ParametresNotifications' })

import { ref, onMounted, onUnmounted } from 'vue'
import { useToasts } from '../../composables/useToasts.js'
import EtatChargement from '../EtatChargement.vue'
import { corpsJsonOuVide } from '../../utils/reponseJson.js'

const { afficherToast } = useToasts()

const chargement = ref(true)
const resendDisponible = ref(false)
const frequenceNotifications = ref('jamais')
const notifArtistes = ref(false)
const notifSalles = ref(false)
const notifArtistesPush = ref(false)
const notifSallesPush = ref(false)

const pushSupporte = ref(false)
const vapidDisponible = ref(false)
const pushPermission = ref('default')
const pushAbonne = ref(false)
const pushEnCours = ref(false)
const testEnCours = ref(false)
let swRegistration = null
let cleVapidPublique = null
const controleur = new AbortController()

async function charger() {
  try {
    const [repConfig, repPush] = await Promise.all([
      fetch('/api/user/config', { signal: controleur.signal }),
      fetch('/api/push', { signal: controleur.signal }),
    ])
    if (repConfig.ok) {
      const config = await repConfig.json()
      resendDisponible.value = config.resendDisponible ?? false
      frequenceNotifications.value = config.frequenceNotifications ?? 'jamais'
      notifArtistes.value = config.notifArtistes ?? false
      notifSalles.value = config.notifSalles ?? false
      notifArtistesPush.value = config.notifArtistesPush ?? false
      notifSallesPush.value = config.notifSallesPush ?? false
    }
    if (repPush.ok) {
      const vapid = await repPush.json()
      vapidDisponible.value = vapid.disponible ?? false
      cleVapidPublique = vapid.clePublique ?? null
    }
    await initialiserPush()
  } catch (e) {
    if (e.name !== 'AbortError') {
      afficherToast('Erreur lors du chargement des notifications.', 'erreur')
    }
  } finally {
    chargement.value = false
  }
}

async function initialiserPush() {
  if (!('serviceWorker' in navigator) || !('PushManager' in window)) {
    pushSupporte.value = false
    return
  }
  pushSupporte.value = true
  pushPermission.value = Notification.permission

  if (vapidDisponible.value && pushPermission.value !== 'denied') {
    try {
      swRegistration = await navigator.serviceWorker.ready
      const abonnement = await swRegistration.pushManager.getSubscription()
      pushAbonne.value = abonnement !== null
    } catch (_) {
      pushAbonne.value = false
    }
  }
}

function urlBase64VersUint8Array(base64) {
  const pad = '='.repeat((4 - (base64.length % 4)) % 4)
  const b64 = (base64 + pad).replace(/-/g, '+').replace(/_/g, '/')
  const raw = atob(b64)
  return Uint8Array.from([...raw].map((c) => c.charCodeAt(0)))
}

async function sauvegarderNotifications() {
  try {
    const rep = await fetch('/api/user/config/notifications', {
      method: 'PATCH',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        frequenceNotifications: frequenceNotifications.value,
        notifArtistes: notifArtistes.value,
        notifSalles: notifSalles.value,
        notifArtistesPush: notifArtistesPush.value,
        notifSallesPush: notifSallesPush.value,
      }),
      signal: controleur.signal,
    })
    if (rep.ok) {
      afficherToast('Préférences de notification enregistrées.', 'succes')
    } else {
      const corps = await corpsJsonOuVide(rep)
      afficherToast(corps.erreur || 'Erreur.', 'erreur')
    }
  } catch (e) {
    if (e.name === 'AbortError') return
    afficherToast('Erreur réseau lors de l\'enregistrement.', 'erreur')
  }
}

async function abonnerPush() {
  if (!swRegistration || !cleVapidPublique) return
  pushEnCours.value = true
  try {
    const permission = await Notification.requestPermission()
    pushPermission.value = permission
    if (permission !== 'granted') return

    const abonnement = await swRegistration.pushManager.subscribe({
      userVisibleOnly: true,
      applicationServerKey: urlBase64VersUint8Array(cleVapidPublique),
    })

    const rep = await fetch('/api/push', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        endpoint: abonnement.endpoint,
        keys: {
          p256dh: new Uint8Array(abonnement.getKey('p256dh')).toBase64(),
          auth: new Uint8Array(abonnement.getKey('auth')).toBase64(),
        },
      }),
      signal: controleur.signal,
    })

    if (rep.ok) {
      pushAbonne.value = true
      document.cookie = 'push_opt_in=1; path=/; max-age=' + 365 * 24 * 3600 + '; SameSite=Strict'
      afficherToast('Notifications push activées.', 'succes')
    } else {
      await abonnement.unsubscribe()
      const corps = await rep.json()
      afficherToast(corps.erreur || "Erreur lors de l'abonnement.", 'erreur')
    }
  } catch (_) {
    afficherToast("Erreur lors de l'abonnement push.", 'erreur')
  } finally {
    pushEnCours.value = false
  }
}

// transmettre l'endpoint de l'appareil courant plutôt que de laisser le serveur
// sélectionner arbitrairement le plus récent — sinon un utilisateur abonné sur plusieurs
// appareils cliquant "Test" depuis l'un d'eux pouvait recevoir la notification sur un autre.
async function testerPush() {
  testEnCours.value = true
  try {
    let endpoint = null
    if (swRegistration) {
      const abonnement = await swRegistration.pushManager.getSubscription()
      endpoint = abonnement?.endpoint ?? null
    }
    const rep = await fetch('/api/push/tester', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ endpoint }),
      signal: controleur.signal,
    })
    if (rep.ok) {
      afficherToast('Notification de test envoyée.', 'succes')
    } else {
      const corps = await rep.json()
      afficherToast(corps.erreur || 'Erreur lors du test.', 'erreur')
    }
  } catch (_) {
    afficherToast('Erreur réseau.', 'erreur')
  } finally {
    testEnCours.value = false
  }
}

async function desabonnerPush() {
  if (!swRegistration) return
  pushEnCours.value = true
  try {
    const abonnement = await swRegistration.pushManager.getSubscription()
    if (abonnement) {
      await fetch('/api/push', {
        method: 'DELETE',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ endpoint: abonnement.endpoint }),
        signal: controleur.signal,
      })
      await abonnement.unsubscribe()
    }
    pushAbonne.value = false
    document.cookie = 'push_opt_in=; path=/; max-age=0; SameSite=Strict'
    afficherToast('Notifications push désactivées.', 'succes')
  } catch (_) {
    afficherToast('Erreur lors du désabonnement push.', 'erreur')
  } finally {
    pushEnCours.value = false
  }
}

onMounted(charger)
onUnmounted(() => controleur.abort())
</script>

<style scoped>
  .param-bloc-push {
    margin-top: 1.5rem;
  }
  .notif-grille__entete,
.notif-grille__ligne {
    display: grid;
    grid-template-columns: 1fr 3rem 3rem;
    align-items: center;
    gap: 0;
    clear: left;
  }
  .notif-grille__entete {
    font-size: 0.75rem;
    font-weight: 500;
    color: var(--fg-muted);
    text-align: center;
    margin-bottom: 0.25rem;
  }
  .notif-grille__entete span:first-child {
    text-align: left;
  }
  .notif-grille__ligne {
    padding: 0.25rem 0;
  }
  .notif-grille__libelle {
    font-size: 0.875rem;
  }
  .notif-grille__case {
    display: flex;
    justify-content: center;
    align-items: center;
    cursor: pointer;
  }
  .notif-radio {
    display: flex;
    align-items: center;
    gap: 0.5rem;
    font-weight: normal;
    cursor: pointer;
  }
  .notif-radio input[type="radio"] {
    appearance: none;
    -webkit-appearance: none;
    width: 1.125rem;
    height: 1.125rem;
    min-width: 1.125rem;
    border: 1.5px solid var(--border);
    border-radius: 50%;
    background: var(--bg);
    cursor: pointer;
    transition:
    background 0.15s,
    border-color 0.15s;
    position: relative;
  }
  .notif-radio input[type="radio"]:checked {
    border-color: var(--primary);
    background: var(--primary);
    box-shadow: inset 0 0 0 3px var(--bg);
  }
  .notif-radio input[type="radio"]:disabled {
    opacity: 0.5;
    cursor: not-allowed;
  }
  .notif-radio:has(input:disabled) {
    cursor: not-allowed;
  }
</style>
