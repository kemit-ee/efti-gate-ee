<script lang="ts">
  import {t} from 'i18n'
  import Navbar from 'src/components/Navbar.svelte'
  import Toasts from 'src/components/Toasts.svelte'
  import {navigate, Route, Router} from 'src/router'
  import {Mode} from 'src/shared/Mode'
  import {user} from 'src/stores/auth'
  import {Role} from 'src/api/types'
  import GatesPage from 'src/pages/admin/gates/GatesPage.svelte'
  import PlatformsPage from 'src/pages/admin/platforms/PlatformsPage.svelte'
  import AuthoritiesPage from 'src/pages/admin/authorities/AuthoritiesPage.svelte'
  import UsersPage from 'src/pages/admin/users/UsersPage.svelte'
  import ConsignmentPage from 'src/pages/admin/consignments/ConsignmentPage.svelte'
  import IdentifiersQueryPage from 'src/pages/authority/identifiers/IdentifiersQueryPage.svelte'
  import DatasetQueryPage from 'src/pages/authority/datasets/DatasetQueryPage.svelte'
  import MetricsPage from 'src/pages/admin/metrics/MetricsPage.svelte'

  let mode = localStorage['mode'] ?? Mode.ADMIN
  $: localStorage['mode'] = mode

  const allRoutes = [
    {name: t.gates.title, path: '/gates', component: GatesPage, mode: Mode.ADMIN, role: Role.GATE},
    {name: t.platforms.title, path: '/platforms', component: PlatformsPage, mode: Mode.ADMIN, role: Role.PLATFORM},
    {name: t.authorities.title, path: '/authorities', component: AuthoritiesPage, mode: Mode.ADMIN, role: Role.AUTHORITY},
    {name: t.users.title, path: '/users', component: UsersPage, mode: Mode.ADMIN},
    {name: t.consignments.title, path: '/consignments', component: ConsignmentPage, mode: Mode.ADMIN, role: Role.PLATFORM},
    {name: t.identifiers.title, path: '/identifiers', component: IdentifiersQueryPage, mode: Mode.AUTHORITY},
    {name: t.datasets.title, path: '/dataset/*uilPath', component: DatasetQueryPage, mode: Mode.AUTHORITY},
    {name: t.metrics.title, path: '/metric', component: MetricsPage, mode: Mode.ADMIN, role: Role.ADMIN},
  ]

  $: routes = allRoutes.filter(r => r.mode === mode && (!r.role || !!$user?.roles[r.role] || $user?.isSuperAdmin))

  function navigateToFirstPage() {
    navigate(routes.first()!.path)
  }

  $: if ($user && location.pathname === '/') navigateToFirstPage()
</script>

<svelte:head>
  {#if mode === Mode.ADMIN}
    <title>{t.admin.title}</title>
  {:else if mode === Mode.AUTHORITY}
    <title>{t.authority.title}</title>
  {/if}
</svelte:head>

<Toasts/>

<Router>
  {#key $user?.id}
    <Navbar bind:mode {routes} onToggleMode={() => setTimeout(() => navigateToFirstPage())}/>
    <main class="min-h-screen p-4 md:p-6 !pt-24">
      {#each allRoutes as r}
        <Route {...r}/>
      {/each}
    </main>
  {/key}
</Router>
