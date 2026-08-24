<script lang="ts">
  import api from 'src/api/api'
  import {t} from 'i18n'
  import Form from 'src/forms/Form.svelte'
  import Button from 'src/components/Button.svelte'
  import FormField from 'src/forms/FormField.svelte'
  import {showToast} from 'src/stores/toasts'
  import SubsetsEditor from 'src/pages/admin/SubsetsEditor.svelte'
  import type {Authority, AuthorityRequest} from 'src/api/ruuterTypes'

  export let authority: Authority
  export let onSaved = () => {}

  const isEdit = !!authority.id

  async function submit() {
    const request: AuthorityRequest = {
      id: authority.id,
      name: authority.name,
      registryCode: authority.registryCode,
      subsets: authority.subsets,
    }

    if (isEdit) await api.put(`v1/authorities/${request.id}`, request)
    else await api.post('v1/authorities', request)

    showToast(isEdit ? t.general.saved : `${t.authorities.added}: ${authority.id}`)
    onSaved()
  }
</script>

<Form {submit}>
  <FormField label={t.authorities.id} bind:value={authority.id} disabled={isEdit}/>
  <FormField label={t.authorities.name} bind:value={authority.name}/>
  <FormField label={t.authorities.registryCode} bind:value={authority.registryCode}/>
  <SubsetsEditor bind:subsets={authority.subsets}/>

  <Button type="submit" label={t.general.save} class="primary"/>
</Form>
