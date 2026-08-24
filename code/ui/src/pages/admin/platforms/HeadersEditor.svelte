<script lang="ts">
  import {t} from 'i18n'
  import Button from 'src/components/Button.svelte'
  import FormField from 'src/forms/FormField.svelte'

  export let headers: [string, string][]

  function addHeader() {
    headers = [...headers, ['', '']]
  }

  function removeHeader(h: string[]) {
    headers = headers.filter(it => it !== h)
  }
</script>

<div>
  <span class="-mb-4">{t.platforms.headers}</span>
</div>
<div class="flex flex-col gap-4">
  {#each headers as h, i}
    <div class="flex w-full justify-between">
      <FormField label={t.platforms.key} bind:value={h[0]} id="key-{i}"/>
      <FormField label={t.platforms.value} bind:value={h[1]} id="value-{i}"/>
      <div class="flex items-end">
        <Button label="×" onclick={() => removeHeader(h)} class="danger" title={t.general.remove}/>
      </div>
    </div>
  {/each}
  <div>
    <Button label="+" onclick={addHeader} title={t.general.add}/>
  </div>
</div>
