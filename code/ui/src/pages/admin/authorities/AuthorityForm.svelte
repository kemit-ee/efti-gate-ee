<script lang="ts">
  import {t} from 'i18n'
  import FormField from 'src/forms/FormField.svelte'
  import SubsetsEditor from 'src/pages/admin/SubsetsEditor.svelte'
  import type {Authority} from 'src/api/ruuterTypes'

  export let authority: Authority
  export let disabled = false
</script>

<div class="spaced">
  <FormField label={t.authorities.id} bind:value={authority.id} disabled/>
  <FormField label={t.authorities.name} bind:value={authority.name} {disabled}/>
  <FormField label={t.authorities.registryCode} bind:value={authority.registryCode} {disabled}/>
  <SubsetsEditor bind:subsets={authority.subsets} {disabled}/>
</div>
