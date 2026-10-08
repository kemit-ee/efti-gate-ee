<script lang="ts">
  import {t} from 'i18n'
  import FormField from 'src/forms/FormField.svelte'
  import EDeliveryFields from 'src/pages/admin/EDeliveryFields.svelte'
  import CountrySelect from 'src/pages/admin/CountrySelect.svelte'
  import type {Gate} from "src/api/ruuterTypes";

  export let gate: Gate
  export let disabled = false
</script>

<div class="spaced">
  <FormField label={t.gates.id} bind:value={gate.id} disabled/>
  <CountrySelect label={t.general.countryCode} bind:countryCode={gate.countryCode} {disabled}/>
  <FormField label={t.gates.eDeliveryUrl} type="url" bind:value={gate.eDeliveryUrl} placeholder={t.gates.eDeliveryUrlPlaceholder} {disabled}/>

  <EDeliveryFields bind:entity={gate} {disabled}/>
</div>
