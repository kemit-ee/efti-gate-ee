<script lang="ts">
  import api from 'src/api/api'
  import {t} from 'i18n'
  import Form from 'src/forms/Form.svelte'
  import Button from 'src/components/Button.svelte'
  import FormField from 'src/forms/FormField.svelte'
  import {showToast} from 'src/stores/toasts'
  import CheckboxField from 'src/forms/CheckboxField.svelte'
  import EDeliveryFields from 'src/pages/admin/EDeliveryFields.svelte'
  import HeadersEditor from 'src/pages/admin/platforms/HeadersEditor.svelte'
  import {type Platform, Status} from "src/api/ruuterTypes";

  export let platform: Platform
  export let onSaved = (platform: Platform, isNew: boolean) => {}

  const isEdit = !!platform.id

  let isPlatformDisabled = platform.status === Status.DISABLED
  $: platform.status = isPlatformDisabled ? Status.DISABLED : Status.OFFLINE

  let eDelivery = !!platform.eDeliveryCert
  $: if (platform.baseUrl?.endsWith('/msh')) eDelivery = true

  let headers = Object.entries(platform.headers ?? {})
  $: platform.headers = Object.fromEntries(headers)

  async function submit() {
    platform.status = platform.status === Status.DISABLED ? Status.DISABLED : Status.ONLINE
    if (!eDelivery) {
      platform.eDeliveryCert = ''
      platform.tlsCert = ''
    }

    await api.post('platforms', platform)
    showToast(isEdit ? t.general.saved : `${t.platforms.added}: ${platform.id}`)
    onSaved(platform, !isEdit)
  }
</script>

<Form {submit}>
  <FormField label={t.platforms.id} bind:value={platform.id} disabled={isEdit}/>
  <FormField label={t.platforms.baseUrl} type="url" bind:value={platform.baseUrl}/>
  <div class="grid grid-cols-2 gap-4">
    <CheckboxField label={t.platforms.eDelivery} bind:checked={eDelivery}/>
    <CheckboxField label={t.platforms.supportsSubsetting} bind:checked={platform.supportsSubsetting}/>
  </div>
  {#if eDelivery}
    <EDeliveryFields bind:entity={platform}/>
  {/if}
  <HeadersEditor bind:headers={headers}/>
  <!-- TODO add xsd version support
  <SelectField label={t.platforms.xsdSupport} options={t.xsdSupport} bind:value={platform.xsdSupport}/>
  -->
  <div class="flex gap-4 items-center">
    <Button type="submit" label={t.general.save} class="primary"/>
    <CheckboxField label={t.platforms.disabled} bind:checked={isPlatformDisabled} class="ml-4"/>
  </div>
</Form>
