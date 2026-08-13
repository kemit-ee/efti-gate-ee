<script lang="ts">
  import {t} from 'i18n'
  import FormField from 'src/forms/FormField.svelte'

  export let label: string|undefined = undefined
  export let value: string | null = ''
  export let maxlength = 1000
  export let rows = (value?.split('\n').length ?? 0) + 1
  export let required = true
  export let validator: ((value: string) => string)|undefined = undefined

  let textarea: HTMLTextAreaElement
  $: tooLong = value?.length || 0 > maxlength
  $: validationError = tooLong ? t.errors.tooLong : (value && validator?.(value)) ?? ''
  $: textarea?.setCustomValidity(validationError)
</script>

<FormField {label} let:id {required} class={$$props.class}>
  <div class="float-right text-sm -mt-6" class:text-danger-600={tooLong}>
    {value?.length ?? 0} / {maxlength}
  </div>
  <textarea {id} bind:this={textarea} bind:value {rows} {required} {...$$restProps}></textarea>
</FormField>
