<script lang="ts">
  import {t} from 'i18n'
  import Navbar from 'src/components/Navbar.svelte'
  import Toasts from 'src/components/Toasts.svelte'
  import {navigate, Route, Router} from 'src/router'
  import AuthCallbackPage from 'src/pages/auth/AuthCallbackPage.svelte'
  import LoginPage from 'src/pages/auth/LoginPage.svelte'
  import GatesPage from 'src/pages/admin/gates/GatesPage.svelte'
  import PlatformsPage from 'src/pages/admin/platforms/PlatformsPage.svelte'
  import AuthoritiesPage from 'src/pages/admin/authorities/AuthoritiesPage.svelte'
  import UsersPage from 'src/pages/admin/users/UsersPage.svelte'
  import ConsignmentPage from "src/pages/admin/consignments/ConsignmentPage.svelte";

  const routes = [
    {name: t.gates.title, path: '/gates', component: GatesPage},
    {name: t.platforms.title, path: '/platforms', component: PlatformsPage},
    {name: t.authorities.title, path: '/authorities', component: AuthoritiesPage},
    {name: t.users.title, path: '/users', component: UsersPage},
    {name: t.consignments.title, path: '/consignments', component: ConsignmentPage},
  ]

  $: if (location.pathname === '/') navigate('/login')
</script>

<svelte:head>
  <title>{t.general.admin}</title>
</svelte:head>

<Toasts/>

<Router>
  <Route path="/login" component={LoginPage}/>
  <Route path="/auth/callback" component={AuthCallbackPage}/>
  <Navbar {routes}/>
  <main class="min-h-screen p-4 md:p-6 !pt-24">
    {#each routes as r}
      <Route {...r}/>
    {/each}
    <Route path="/auth/callback" component={AuthCallbackPage}/>
  </main>
</Router>
