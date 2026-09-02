<script lang="ts">
  import PlatformList from 'src/pages/admin/platforms/PlatformList.svelte'
  import {onMount} from 'svelte'
  import api from 'src/api/api'
  import {t} from 'i18n'
  import Modal from 'src/components/Modal.svelte'
  import Button from 'src/components/Button.svelte'
  import PlatformForm from 'src/pages/admin/platforms/PlatformForm.svelte'
  import type {Platform} from "src/api/ruuterTypes";
  import OwnGateButton from "src/pages/admin/gates/OwnGateButton.svelte";

  let platforms: Platform[]
  let editPlatform: Platform | false = false

  onMount(load)

  async function load() {
    platforms = await api.get<Platform[]>('platforms')
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


<div class="mb-6 flex justify-between items-center gap-8">
  <h1>
    {t.platforms.title} ({platforms?.length})
  </h1>
  <div>
    <OwnGateButton/>
    <Button label={t.general.add} onclick={add} class="primary"/>
  </div>
</div>

<PlatformList {platforms} onEdit={onEdit} onDeleted={load} onChanged={load}/>

<Modal bind:show={editPlatform} title={t.platforms.platform}>
  {#if editPlatform}
    <PlatformForm platform={editPlatform} {onSaved}/>
  {/if}
</Modal>
