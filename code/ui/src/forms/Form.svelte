<script lang="ts">
  import type {FormEventHandler} from 'svelte/elements'

  export let submit: FormEventHandler<HTMLFormElement>

  export let validated = false
</script>

<form on:invalid|capture={() => validated = true} class:validated
      on:submit|preventDefault={submit} class={$$props.class ?? 'spaced'} {...$$restProps}>
  <slot/>
</form>

<style>
  .validated :global(input:invalid), .validated :global(select:invalid), .validated :global(textarea:invalid), .validated :global(.invalid) {
    @apply border-danger-500 ring-danger-500 focus:border-danger-500 focus:ring-danger-500;
  }
</style>
