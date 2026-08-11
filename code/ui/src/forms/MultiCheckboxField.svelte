<script lang="ts">
  import FormField from './FormField.svelte'

  export let label: string|undefined = undefined
  export let value: string[] = []
  export let options: {[value: string]: string}
  export let disabled = false
  export let helpText = ''

  function toggle(option: string) {
    if (value?.includes(option)) {
      value = value.filter(v => v !== option)
    } else {
      value = [...(value ?? []), option]
    }
  }
</script>

<FormField {label} {helpText} class={$$props.class}>
  <div class="flex flex-wrap gap-x-4 gap-y-2">
    {#each Object.entries(options) as [option, label] (option)}
      <label class="flex items-center gap-2" class:opacity-50={disabled}>
        <input type="checkbox" checked={value?.includes(option)} on:change={() => toggle(option)} {disabled}>
        <span class="text-sm">{label}</span>
      </label>
    {/each}
  </div>
</FormField>

<style>
  :global(input[type=checkbox]) {
    @apply focus:ring-primary-500 h-4 w-4 text-primary-500 border-gray-300 rounded;
  }
</style>
