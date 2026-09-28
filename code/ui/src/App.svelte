<script lang="ts">
  import {t} from 'i18n'
  import Navbar from 'src/components/Navbar.svelte'
  import Toasts from 'src/components/Toasts.svelte'
  import {activePath, Route, Router} from 'src/router'
  import AuthCallbackPage from 'src/pages/auth/AuthCallbackPage.svelte'
  import GatesPage from 'src/pages/admin/gates/GatesPage.svelte'
  import PlatformsPage from 'src/pages/admin/platforms/PlatformsPage.svelte'
  import AuthoritiesPage from 'src/pages/admin/authorities/AuthoritiesPage.svelte'
  import UsersPage from 'src/pages/admin/users/UsersPage.svelte'
  import ConsignmentPage from "src/pages/admin/consignments/ConsignmentPage.svelte"
  import api, {getToken} from "src/api/api"
  import type {TaraLoginResponse, User} from "src/api/ruuterTypes"
  import {currentUser} from "src/stores/session"

  const routes = [
    {name: t.gates.title, path: '/gates', component: GatesPage},
    {name: t.platforms.title, path: '/platforms', component: PlatformsPage},
    {name: t.authorities.title, path: '/authorities', component: AuthoritiesPage},
    {name: t.users.title, path: '/users', component: UsersPage},
    {name: t.consignments.title, path: '/consignments', component: ConsignmentPage},
  ]

  let user: User | undefined
  let authError: string | null = null
  $: if (!user && !authError && $activePath !== '/callback') getUser()

  async function getUser() {
    if (!getToken()) {
      await redirectToTara()
      return
    }

    try {
      user = await api.get<User>('/auth/user')
      currentUser.set(user)
    } catch {
      await redirectToTara()
    }
  }

  // A misconfigured or unreachable TARA/TIM must not send the user back into getUser() on every
  // failed attempt: without `authError` gating the reactive statement above, a fetch failure here,
  // a non-JSON/error response from TIM, or a response missing authorization_url would leave `user`
  // unset forever, and every re-render (e.g. from an unrelated store update) would retrigger this
  // same request indefinitely.
  async function redirectToTara() {
    try {
      const res = await fetch(`/tim/auth/login/tara?redirect_uri=${location.origin}/callback`)
      const data: TaraLoginResponse = await res.json()
      if (!res.ok || !data?.authorization_url) throw new Error('TIM did not return an authorization_url')
      location.href = import.meta.env.VITE_USE_PROD_TARA_URL === 'true'
        ? data.authorization_url
        : data.authorization_url.replace('https://tara-mock:8080', '/tara')
    } catch {
      authError = t.auth.taraUnavailable
    }
  }
</script>

<svelte:head>
  <title>{t.general.admin}</title>
</svelte:head>

<Toasts/>

{#if authError}
  <main class="min-h-screen p-4 md:p-6 flex items-center justify-center text-center">
    <p>{authError}</p>
  </main>
{:else}
<Router>
  <Navbar {routes} {user}/>
  <main class="min-h-screen p-4 md:p-6 !pt-24">
    {#each routes as r}
      <Route {...r}/>
    {/each}
    <Route path="/callback" component={AuthCallbackPage}/>
  </main>
</Router>
{/if}
