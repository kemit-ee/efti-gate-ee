<script lang="ts">
  import {t} from 'i18n'
  import Navbar from 'src/components/Navbar.svelte'
  import Toasts from 'src/components/Toasts.svelte'
  import {navigate, Route, Router} from 'src/router'
  import {user} from 'src/stores/auth'
  import {Role} from 'src/api/types'
  import GatesPage from 'src/pages/admin/gates/GatesPage.svelte'
  import PlatformsPage from 'src/pages/admin/platforms/PlatformsPage.svelte'
  import AuthoritiesPage from 'src/pages/admin/authorities/AuthoritiesPage.svelte'
  import UsersPage from 'src/pages/admin/users/UsersPage.svelte'
  import ConsignmentPage from 'src/pages/admin/consignments/ConsignmentPage.svelte'

  const routes = [
    {name: t.gates.title, path: '/gates', component: GatesPage, role: Role.GATE},
    {name: t.platforms.title, path: '/platforms', component: PlatformsPage, role: Role.PLATFORM},
    {name: t.authorities.title, path: '/authorities', component: AuthoritiesPage, role: Role.AUTHORITY},
    {name: t.users.title, path: '/users', component: UsersPage},
    {name: t.consignments.title, path: '/consignments', component: ConsignmentPage, role: Role.PLATFORM},
  ]

  function navigateToFirstPage() {
    navigate(routes.first()!.path)
  }

  $: if ($user && location.pathname === '/') navigateToFirstPage()
</script>

<svelte:head>
  <title>{t.general.admin}</title>
</svelte:head>

<Toasts/>

<Router>
  {#key $user?.id}
    <Navbar {routes}/>
    <main class="min-h-screen p-4 md:p-6 !pt-24">
      {#each routes as r}
        <Route {...r}/>
      {/each}
    </main>
  {/key}
</Router>
