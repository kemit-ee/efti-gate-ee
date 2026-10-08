<script lang="ts">
  import {t} from 'i18n'
  import FormField from 'src/forms/FormField.svelte'
  import CheckboxField from 'src/forms/CheckboxField.svelte'
  import EDeliveryFields from 'src/pages/admin/EDeliveryFields.svelte'
  import HeadersEditor from 'src/pages/admin/platforms/HeadersEditor.svelte'
  import type {Platform} from "src/api/ruuterTypes";

  export let platform: Platform
  export let disabled = false

  $: eDelivery = !!platform.eDeliveryCert || !!platform.baseUrl?.endsWith('/msh')

  let headers = Object.entries(platform.headers ?? {})
</script>

<div class="spaced">
  <FormField label={t.platforms.id} bind:value={platform.id} disabled/>
  <FormField label={t.platforms.baseUrl} type="url" bind:value={platform.baseUrl} {disabled}/>
  <CheckboxField label={t.platforms.eDelivery} checked={eDelivery} disabled/>
  {#if eDelivery}
    <EDeliveryFields bind:entity={platform} {disabled}/>
  {/if}
  <HeadersEditor bind:headers {disabled}/>
</div>
