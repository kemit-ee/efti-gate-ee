<script lang="ts">
  import api from 'src/api/api'
  import {t} from 'i18n'
  import Form from 'src/forms/Form.svelte'
  import Button from 'src/components/Button.svelte'
  import FormField from 'src/forms/FormField.svelte'
  import {showToast} from 'src/stores/toasts'
  import CheckboxField from 'src/forms/CheckboxField.svelte'
  import EDeliveryFields from 'src/pages/admin/EDeliveryFields.svelte'
  import CountrySelect from 'src/pages/admin/CountrySelect.svelte'
  import {type GateRequest, type Gate, Status} from "src/api/ruuterTypes";

  export let gate: Gate
  export let onSaved = () => {}
  export let disabled = false

  let isGateDisabled = gate.status === Status.DISABLED

  const isEdit = !!gate.id

  async function submit() {
    gate.status = gate.status === Status.DISABLED ? Status.DISABLED : Status.OFFLINE
    const request: GateRequest = {
      id: gate.id,
      countryCode: gate.countryCode,
      eDeliveryUrl: gate.eDeliveryUrl,
      eDeliveryCert: gate.eDeliveryCert,
      tlsCert: gate.tlsCert,
      status: gate.status
    }
    if (isEdit) await api.put(`v1/gates/update?gateId=${request.id}`, request)
    else await api.post('v1/gates', request)
    if (gate.status !== Status.DISABLED) await api.post(`v1/gates/ping?gateId=${gate.id}`).catch(() => {})
    showToast(isEdit ? t.general.saved : `${t.gates.added}: ${gate.id}`)
    onSaved()
  }

  $: gate.status = isGateDisabled ? Status.DISABLED : Status.OFFLINE
</script>

<Form {submit}>
  <FormField label={t.gates.id} bind:value={gate.id} disabled={disabled || isEdit}/>
  <CountrySelect label={t.general.countryCode} bind:countryCode={gate.countryCode} {disabled}/>
  <FormField label={t.gates.eDeliveryUrl} type="url" bind:value={gate.eDeliveryUrl} placeholder={t.gates.eDeliveryUrlPlaceholder} {disabled}/>

  <EDeliveryFields bind:entity={gate} {disabled}/>

  <!--TODO add xsd support
  <SelectField label={t.gates.xsdSupport} options={t.xsdSupport} bind:value={gate.xsdSupport} {disabled}/>
  -->

  {#if !disabled}
    <div class="flex gap-4 items-center">
      <Button type="submit" label={t.general.save} class="primary"/>
      <CheckboxField label={t.gates.disabled} bind:checked={isGateDisabled} class="ml-4"/>
    </div>
  {/if}
</Form>
