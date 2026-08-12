<script lang="ts">
  import FormField from 'src/forms/FormField.svelte'
  import Icon from 'src/icons/Icon.svelte'
  import PlusSelectField from 'src/forms/PlusSelectField.svelte'
  import Badge from 'src/components/Badge.svelte'

  export let label = ''
  export let values: string[]
  export let options: {[value: string|number]: string|number} | string[] | string | undefined
  export let disabled = false
  export let required = true
  export let emptyOption = "+"
  export let onchange: ((values: string[]) => void) | undefined = undefined

  let select: HTMLSelectElement

  function remove(id: string) {
    values = values.filter(v => v != id)
    onchange?.(values)
  }

  function add() {
    values = [...values ?? [], select.value]
    onchange?.(values)
  }

</script>

<FormField {label} {required}>
  <div class="flex flex-row flex-wrap items-center gap-2">
    {#each values ?? [] as key}
      <Badge>{options?.[key]} {#if !disabled}<button type="button" onclick={() => remove(key)} class="ml-1"><Icon name="x"/></button>{/if}</Badge>
    {/each}
    {#if !disabled}
      <PlusSelectField {options} {values} {emptyOption} bind:select onchange={add} {...$$restProps}/>
    {/if}
  </div>
</FormField>
