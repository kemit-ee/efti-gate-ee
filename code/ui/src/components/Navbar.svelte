<script lang="ts">
  import {changeLang, lang, t} from 'i18n'
  import langs from 'i18n/langs.json'
  import {activePath, Link} from 'src/router'
  import {type NavRoute} from 'src/shared/Mode'
  import Button from 'src/components/Button.svelte'
  import {user, userSwitch} from 'src/stores/auth'
  import SelectField from 'src/forms/SelectField.svelte'

  export let routes: NavRoute[]

  const isMobile = innerWidth < 640
  let menuOpen = !isMobile
  let selectedLang = lang
  $: if (selectedLang !== lang) changeLang(selectedLang)

  $: isActive = (path: string) => $activePath.split('/')[1] === path.split('/')[1]
</script>

<nav class="fixed w-full top-0 z-40 bg-primary-700 shadow-lg text-white">
  <div class="px-4 sm:px-6 lg:px-8 flex h-16 items-center justify-between">
    <div class="flex items-center gap-4">
      <h1 class="text-lg font-bold">{t.general.admin}</h1>
      {#if import.meta.env.DEV}
        <h5 class="font-black text-danger-400">DEV</h5>
      {/if}

      {#if menuOpen}
        <div class="flex flex-col max-sm:bg-primary-800 gap-4 sm:flex-row sm:ml-8 max-sm:fixed max-sm:top-16 max-sm:mt-1 max-sm:left-0 max-sm:right-0 max-sm:p-4 max-sm:border-b">
          {#each routes as link}
            <Link to={link.path.replace(/\/\*.*/, '')} on:click={() => {if (isMobile) menuOpen = false}}
              class="inline-flex items-center px-1 pt-1 flex-1 text-white hover:text-primary-200
                    {isActive(link.path) ? 'font-bold' : 'font-medium'}">
              {link.name}
            </Link>
          {/each}
        </div>
      {/if}
    </div>

    <div class="flex items-center gap-2">
      {#if $user}
        <Button class="sm secondary-inverted" onclick={userSwitch}>{$user.name}</Button>
      {/if}

      <SelectField
        options={Object.fromEntries(langs.map(l => [l, l.toUpperCase()]))}
        bind:value={selectedLang}
        required={false}
      />
    </div>

    <div class="flex items-center sm:hidden">
      <Button label={menuOpen ? '✕' : '☰'} onclick={() => menuOpen = !menuOpen}/>
    </div>
  </div>
</nav>
