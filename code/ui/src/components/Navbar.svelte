<script lang="ts">
  import {changeLang, lang, t} from 'i18n'
  import langs from 'i18n/langs.json'
  import {activePath, Link} from 'src/router'
  import type {NavRoute} from 'src/shared/Mode'
  import Icon from "src/icons/Icon.svelte";
  import {onMount} from 'svelte';
  import api, {clearToken} from 'src/api/api';
  import type {User} from "src/api/ruuterTypes";
  import Dropdown from "src/components/Dropdown.svelte";

  export let routes: NavRoute[]
  export let user: User | undefined
  let isMobile = false
  let menuOpen = true
  let selectedLang = lang

  onMount(() => {
    const mediaQuery = window.matchMedia('(max-width: 639px)')
    isMobile = mediaQuery.matches
    menuOpen = !isMobile

    const handleChange = (e: MediaQueryListEvent) => {
      isMobile = e.matches
      menuOpen = !isMobile
    }

    mediaQuery.addEventListener('change', handleChange)
    return () => mediaQuery.removeEventListener('change', handleChange)
  })

  $: if (selectedLang !== lang) changeLang(selectedLang)

  function isActive(path: string, currentPath: string) {
    return currentPath.split('/')[1] === path.split('/')[1]
  }

  function toggleMenu() {
    menuOpen = !menuOpen
  }

  function handleLinkClick() {
    if (isMobile) menuOpen = false
  }

  async function logout() {
    await api.post('/auth/logout').catch(() => {})
    clearToken()
    window.location.href = '/'
  }
</script>

{#snippet menu()}
  <button class='w-full text-left px-4 py-2 text-gray-700 hover:bg-gray-100 transition-colors' onclick={logout}>
    {t.auth.logout}
  </button>
{/snippet}

<header class="w-full shadow-md z-40 bg-white">
  <div class="flex items-stretch justify-between sm:items-center sm:py-4 sm:px-6 lg:px-8 h-16 sm:h-auto">
    <button class="flex sm:hidden flex-col items-center justify-center bg-primary-700 text-white px-4 hover:bg-primary-800 transition-colors order-1" onclick={toggleMenu}>
      <Icon name="menu" class="size-7 mb-0.5"/>
      <span class="text-sm font-medium">{t.general.menu}</span>
    </button>

    <div class="flex items-center gap-3 order-2 sm:order-none mr-auto ml-4 sm:ml-0 py-2 sm:py-0">
      <img src="/kemit-logo.svg" alt={t.general.systemManagerName} class="h-6 sm:h-10">
      <div class="flex flex-col text-primary-700">
        <span class="text-sm">{t.general.systemName}</span>
        <span class="text-xs font-bold">{t.general.systemManagerName}</span>
      </div>
    </div>

    <div class="hidden sm:flex items-center gap-4 sm:gap-6 order-3 sm:order-none px-4 sm:px-0">
      {#if user}
        <div class="flex flex-col gap-2">
          <span>
            {t.users.taraSub}: {user.taraSub}
          </span>
          <Dropdown {menu}>
            <div class="flex gap-2 text-primary-600 items-center hover:underline">
              {user.name} <Icon name="chevron-down"/>
            </div>
          </Dropdown>
        </div>
        <div class="w-px h-12 bg-neutral-400"></div>
      {/if}
      <div class="flex flex-row sm:flex-col items-center sm:items-start gap-2 sm:gap-0">
        <span class="hidden sm:block text-sm text-neutral-700">{t.general.language}</span>
        <select class="text-primary-600 font-medium bg-transparent border-none p-0 pr-8 cursor-pointer uppercase appearance-none" bind:value={selectedLang}>
          {#each langs as l}
            <option value={l}>{l}</option>
          {/each}
        </select>
      </div>
    </div>
    <div class="flex sm:hidden items-center gap-4 order-3 sm:order-none px-4 sm:px-0">
      <div class="flex flex-row sm:flex-col items-center sm:items-start gap-2 sm:gap-0">
        <select class="text-primary-600 font-medium bg-transparent border-none p-0 pr-8 focus:ring-0 cursor-pointer uppercase appearance-none" bind:value={selectedLang}>
          {#each langs as l}
            <option value={l}>{l}</option>
          {/each}
        </select>
      </div>
    </div>
  </div>

  {#if menuOpen}
    <nav class="bg-primary-600 w-full">
      <div class="px-4 sm:px-6 lg:px-8 flex flex-col sm:flex-row">
        {#if isMobile && user}
          <div class="flex flex-col gap-1 py-3 text-white border-b border-primary-500 mb-1">
            <span class="text-sm font-medium">{user.name}</span>
            <span class="text-xs opacity-80">{t.users.taraSub}: {user.taraSub}</span>
            <button class="text-sm text-left font-medium hover:underline mt-1" onclick={logout}>
              {t.auth.logout}
            </button>
          </div>
        {/if}
        {#each routes as link}
          <Link to={link.path.replace(/\/\*.*/, '')} onclick={handleLinkClick} class="px-4 py-3 no-underline text-white hover:text-white transition-colors {isActive(link.path, $activePath) ? 'bg-primary-800 font-bold' : 'font-medium hover:bg-primary-700'}">
            {link.name}
          </Link>
        {/each}
      </div>
    </nav>
  {/if}
</header>