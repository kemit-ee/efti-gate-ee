<script lang="ts">
  import PlatformList from 'src/pages/admin/platforms/PlatformList.svelte'
  import {onMount} from 'svelte'
  import api from 'src/api/api'
  import {t} from 'i18n'
  import Modal from 'src/components/Modal.svelte'
  import PlatformForm from 'src/pages/admin/platforms/PlatformForm.svelte'
  import type {Platform} from "src/api/ruuterTypes";
  import OwnGateButton from "src/pages/admin/gates/OwnGateButton.svelte";

  let platforms: Platform[]
  let detailsPlatform: Platform | false = false

  onMount(load)

  async function load() {
    platforms = await api.get<Platform[]>('platforms')
  }
</script>


<div class="mb-6 flex justify-between items-center gap-8">
  <h1>
    {t.platforms.title} ({platforms?.length})
  </h1>
  <div>
    <OwnGateButton/>
  </div>
</div>

<PlatformList {platforms} onDetails={platform => detailsPlatform = platform}/>

<Modal bind:show={detailsPlatform} title={t.platforms.platform}>
  {#if detailsPlatform}
    <PlatformForm platform={detailsPlatform} disabled/>
  {/if}
</Modal>
