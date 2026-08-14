<script lang="ts">
  import PlatformList from 'src/pages/admin/platforms/PlatformList.svelte'
  import {onMount} from 'svelte'
  import api from 'src/api/api'
  import {t} from 'i18n'
  import Modal from 'src/components/Modal.svelte'
  import Button from 'src/components/Button.svelte'
  import PlatformForm from 'src/pages/admin/platforms/PlatformForm.svelte'
  import type {Platform} from "src/api/ruuterTypes";

  let platforms: Platform[]
  let editPlatform: Platform | false = false

  onMount(load)

  async function load() {
    platforms = await api.get<Platform[]>('v1/platforms')
  }

  function add() {
    editPlatform = {} as Platform
  }

  function onEdit(platform: Platform) {
    editPlatform = platform
  }

  function onSaved() {
    editPlatform = false
    load()
  }
</script>

<h1 class="flex justify-between items-center gap-8 mb-6">
  {t.platforms.title} ({platforms?.length})
  <Button label={t.general.add} onclick={add} class="primary"/>
</h1>

<PlatformList {platforms} onEdit={onEdit} onDeleted={load}/>

<Modal bind:show={editPlatform} title={t.platforms.platform}>
  {#if editPlatform}
    <PlatformForm platform={editPlatform} {onSaved}/>
  {/if}
</Modal>
