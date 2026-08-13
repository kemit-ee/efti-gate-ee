<script lang="ts">
  import PlatformList from 'src/pages/admin/platforms/PlatformList.svelte'
  import {onMount} from 'svelte'
  import api from 'src/api/api'
  import {t} from 'i18n'
  import Modal from 'src/components/Modal.svelte'
  import Button from 'src/components/Button.svelte'
  import PlatformForm from 'src/pages/admin/platforms/PlatformForm.svelte'
  import {showToast, ToastType} from 'src/stores/toasts'
  import {navigate} from 'src/router'
  import OwnGateButton from 'src/pages/admin/gates/OwnGateButton.svelte'
  import {user} from 'src/stores/auth'
  import PlatformTestForm from 'src/pages/admin/platforms/PlatformTestForm.svelte'
  import type {Platform} from "src/api/ruuterTypes";

  let platforms: Platform[]
  let editPlatform: Platform | false = false
  let testPlatform: Platform | false = false

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

  function onTest(platform: Platform) {
    testPlatform = platform
  }

  function onSaved(platform: Platform, isNew: boolean) {
    editPlatform = false
    if (isNew) {
      showToast(t.users.createForAdmin, {type: ToastType.INFO, timeoutSec: 10})
      navigate(`/users?platformId=${platform.id}`)
    } else load()
  }
</script>

<h1 class="flex justify-between items-center gap-8 mb-6">
  {t.platforms.title} ({platforms?.length})
  <span>
    <OwnGateButton/>
    <Button label={t.general.add} onclick={add} class="primary"/>
  </span>
</h1>

<PlatformList {platforms} onEdit={onEdit} onDeleted={load} onTest={onTest}/>

<Modal bind:show={editPlatform} title={t.platforms.platform}>
  {#if editPlatform}
    <PlatformForm platform={editPlatform} {onSaved}/>
  {/if}
</Modal>

<Modal bind:show={testPlatform} title="{t.general.test}: {testPlatform ? testPlatform.id : ''}">
  {#if testPlatform}
    <PlatformTestForm platform={testPlatform}/>
  {/if}
</Modal>
