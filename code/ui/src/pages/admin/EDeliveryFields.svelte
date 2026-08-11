<script lang="ts">
  import {t} from 'i18n'
  import TextAreaField from 'src/forms/TextAreaField.svelte'

  export let entity: {eDeliveryCert?: string, tlsCert?: string}
  export let disabled = false

  async function handleFileChange(e: Event) {
    const input = e.target as HTMLInputElement
    const certFile = input.files && input.files.length > 0 ? input.files[0] : null
    entity[input.id] = await certFile!.text()
  }

  function certValidator(cert: string) {
    return cert.includes('-BEGIN CERTIFICATE-') ? '' : t.errors.invalidCert
  }
</script>

<TextAreaField label={t.platforms.eDeliveryCert} bind:value={entity.eDeliveryCert} rows={3} maxlength={5000} validator={certValidator} {disabled}/>
{#if !disabled}
  <input type="file" id="eDeliveryCert" accept=".pem,.crt" onchange={handleFileChange} class="w-full">
{/if}

<TextAreaField label={t.platforms.tlsCert} bind:value={entity.tlsCert} required={false} rows={3} maxlength={5000} validator={certValidator} {disabled}/>
{#if !disabled}
  <input type="file" id="tlsCert" accept=".pem,.crt" onchange={handleFileChange} class="w-full">
{/if}
