<script lang="ts">
  import {type Authority} from 'src/api/types'
  import api from 'src/api/api'
  import {t} from 'i18n'
  import Form from 'src/forms/Form.svelte'
  import Button from 'src/components/Button.svelte'
  import FormField from 'src/forms/FormField.svelte'
  import {showToast} from 'src/stores/toasts'
  import CountrySelect from 'src/pages/admin/CountrySelect.svelte'
  import SubsetsEditor from 'src/pages/admin/SubsetsEditor.svelte'

  export let authority: Authority
  export let onSaved = (authority: Authority, isNew: boolean) => {}

  const isEdit = !!authority.id

  async function submit() {
    await api.post('authorities', authority)
    showToast(isEdit ? t.general.saved : `${t.authorities.added}: ${authority.id}`)
    onSaved(authority, !isEdit)
  }
</script>

<Form {submit}>
  <FormField label={t.authorities.id} bind:value={authority.id} disabled={isEdit}/>
  <CountrySelect bind:countryCode={authority.countryCode}/>
  <SubsetsEditor countryCode={authority.countryCode} bind:subsets={authority.subsets}/>

  <Button type="submit" label={t.general.save} class="primary"/>
</Form>
