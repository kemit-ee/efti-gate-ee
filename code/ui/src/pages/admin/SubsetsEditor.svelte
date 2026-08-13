<script lang="ts">
  import {t} from 'i18n'
  import Button from 'src/components/Button.svelte'
  import FormField from 'src/forms/FormField.svelte'
  import CheckboxField from 'src/forms/CheckboxField.svelte'
  import {tick} from 'svelte'
  import type {CountryCode, Subset} from "src/api/ruuterTypes";

  export let countryCode: CountryCode
  export let subsets: Subset[]

  let full = !!subsets.find(s => s === 'full')

  async function add() {
    subsets = [...subsets, '']
    await tick();
    ([...document.querySelectorAll('.subset input')] as HTMLInputElement[]).last()?.focus()
  }

  function remove(i: number) {
    subsets.splice(i, 1)
    subsets = subsets
  }

  function fullChange() {
    if (full) subsets = ['full']
    else subsets = ['']
  }
</script>

<div class="flex flex-col gap-2">
  <label>{t.authorities.subsets}</label>
  {#if !full}
    {#each subsets as subset, i}
      <div class="flex gap-2">
        <FormField bind:value={subset} pattern="[A-Z][A-Z][0-9][0-9][a-z]?" maxlength={5} placeholder="{countryCode}01" class="subset"/>
        <Button label="×" onclick={() => remove(i)} title={t.general.remove} class="danger"/>
      </div>
    {/each}
  {/if}
  <div class="flex gap-6 items-center">
    {#if !full}
      <Button label="+" onclick={add} title={t.general.add}/>
    {/if}
    <CheckboxField label="Full" bind:checked={full} onchange={fullChange}/>
  </div>
</div>
