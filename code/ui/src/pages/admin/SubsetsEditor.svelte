<script lang="ts">
  import {t} from 'i18n'
  import Button from 'src/components/Button.svelte'
  import FormField from 'src/forms/FormField.svelte'
  import {tick} from 'svelte'
  import type {Subset} from 'src/api/ruuterTypes'

  export let subsets: Subset[]
  $: if (subsets.length < 1) add()

  $: subsets, validate()

  async function validate() {
    await tick()
    const inputs = document.querySelectorAll('.subset input') as NodeListOf<HTMLInputElement>
    inputs.forEach(input => {
      input.setCustomValidity((input.value ? subsets.filter(s => s === input.value).length > 1 : false) ? t.errors.duplicate : '')
    })
  }

  async function add() {
    subsets = [...subsets, '']
    await tick()
    const inputs = Array.from(document.querySelectorAll('.subset input')) as HTMLInputElement[]
    inputs.pop()?.focus()
  }

  function remove(i: number) {
    if (subsets.length <= 1) return
    subsets.splice(i, 1)
    subsets = subsets
  }
</script>

<div class="flex flex-col gap-2">
  <span>{t.authorities.subsets}</span>
  {#each subsets as subset, i}
    <div class="flex gap-2 subset">
      <FormField maxlength={5} list="estonia-subsets" bind:value={subset}/>
      <datalist id="estonia-subsets">
        <option value="EE01"></option>
        <option value="EE02"></option>
        <option value="EE04"></option>
        <option value="EE05a"></option>
        <option value="EE05c"></option>
        <option value="EE05d"></option>
        <option value="EU01"></option>
        <option value="EU02"></option>
        <option value="EU03"></option>
        <option value="EU04"></option>
        <option value="EU05"></option>
        <option value="EU06"></option>
        <option value="EU07"></option>
      </datalist>
      {#if subsets.length > 1}
        <Button label="×" onclick={() => remove(i)} title={t.general.remove} class="danger"/>
      {/if}
    </div>
  {/each}
  <div class="flex gap-6 items-center">
    <Button label="+" onclick={add} title={t.general.add}/>
  </div>
</div>