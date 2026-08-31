<script lang="ts">
  import {t} from 'i18n'
  import Modal from 'src/components/Modal.svelte'
  import Button from 'src/components/Button.svelte'
  import {showToast} from 'src/stores/toasts'
  import type {PlatformApiKey} from 'src/api/ruuterTypes'

  export let result: PlatformApiKey | false = false
  export let onClose: () => void = () => {}

  let copied = false

  async function copy() {
    await navigator.clipboard.writeText((result as PlatformApiKey).apiKey)
    copied = true
    showToast(t.general.copied)
  }

  $: show = !!result
  $: if (!show && (copied || result === false)) { copied = false }

  function onShow(v: any) {
    if (!v && result) { result = false; copied = false; onClose() }
  }
  $: onShow(show)
</script>

<Modal bind:show title={t.platforms.apiKeyModalTitle}>
  {#if result}
    <p class="mb-4 text-sm text-danger-700">{t.platforms.apiKeyModalWarning}</p>
    <div class="flex items-center gap-2">
      <code class="flex-1 break-all rounded bg-neutral-100 p-3 text-sm">{result.apiKey}</code>
      <Button label={copied ? t.general.copied : t.general.copy} onclick={copy} class="primary"/>
    </div>
    <p class="mt-4 text-xs text-neutral-600">{t.platforms.id}: {result.id}</p>
  {/if}
</Modal>
