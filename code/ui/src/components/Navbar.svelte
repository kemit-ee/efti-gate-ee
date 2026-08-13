<script lang="ts">
  import {changeLang,lang,t} from 'i18n'
  import langs from 'i18n/langs.json'
  import {activePath,Link} from 'src/router'
  import type {NavRoute} from 'src/shared/Mode'
  import {user,userSwitch} from 'src/stores/auth'
  import SelectField from 'src/forms/SelectField.svelte'
  import Icon from "src/icons/Icon.svelte";

  export let routes: NavRoute[]

  const isMobile = innerWidth < 640
  let menuOpen = !isMobile
  let selectedLang = lang

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
</script>

<header class="w-full shadow-md z-40 bg-white">
  <div class="flex items-stretch justify-between sm:items-center sm:py-4 sm:px-6 lg:px-8 h-16 sm:h-auto">
    <button class="flex sm:hidden flex-col items-center justify-center bg-primary-700 text-white px-4 hover:bg-primary-800 transition-colors order-1" onclick={toggleMenu}>
      <Icon name="menu" class="size-7 mb-0.5"/>
      <span class="text-sm font-medium">{t.general.menu}</span>
    </button>

    <div class="flex items-center gap-3 order-2 sm:order-none mr-auto ml-4 sm:ml-0 py-2 sm:py-0">
      <img src="/src/icons/kemit-logo.svg" alt={t.general.systemManagerName} class="h-6 sm:h-10">
      <div class="flex flex-col text-primary-700">
        <span class="text-sm">{t.general.systemName}</span>
        <span class="text-xs font-bold">{t.general.systemManagerName}</span>
      </div>
    </div>

    <div class="flex items-center gap-4 sm:gap-6 order-3 sm:order-none px-4 sm:px-0">
      {#if $user}
        <div class="hidden sm:flex flex-col items-end">
          <button class="text-primary-600 font-medium flex items-center gap-1 hover:text-primary-800" onclick={userSwitch}>
            {$user.name}
          </button>
        </div>
        <div class="hidden sm:block w-px h-10 bg-neutral-350"></div>
      {/if}
      <div class="flex flex-row sm:flex-col items-center sm:items-start gap-2 sm:gap-0">
        <span class="hidden sm:block text-sm text-neutral-700">{t.general.language}</span>
        <SelectField options={Object.fromEntries(langs.map(l => [l, l.toUpperCase()]))} bind:value={selectedLang} required={false}/>
      </div>
    </div>
  </div>

  {#if menuOpen}
    <nav class="bg-primary-600 w-full">
      <div class="px-4 sm:px-6 lg:px-8 flex flex-col sm:flex-row">
        {#each routes as link}
          <Link to={link.path.replace(/\/\*.*/, '')} onclick={handleLinkClick} class="px-4 py-3 no-underline text-white hover:text-white transition-colors {isActive(link.path, $activePath) ? 'bg-primary-800 font-bold' : 'font-medium hover:bg-primary-700'}">
            {link.name}
          </Link>
        {/each}
      </div>
    </nav>
  {/if}
</header>