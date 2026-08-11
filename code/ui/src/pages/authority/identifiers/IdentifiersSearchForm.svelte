<script lang="ts">
  import {t} from 'i18n'
  import Form from 'src/forms/Form.svelte'
  import FormField from 'src/forms/FormField.svelte'
  import Button from 'src/components/Button.svelte'
  import CheckboxField from 'src/forms/CheckboxField.svelte'
  import {CountryCode, type IdentifiersQuery, IdentifierType} from 'src/api/types'
  import SelectField from 'src/forms/SelectField.svelte'
  import MultipleSelect from 'src/forms/MultipleSelect.svelte'

  export let query: IdentifiersQuery = {identifier: { types: [] }} as unknown as IdentifiersQuery
  export let submit: () => void
  let identifierTypes: string[] = query.identifier.types ?? []
  $: query.identifier.types = identifierTypes as IdentifierType[]
</script>

<Form {submit} class="grid md:grid-cols-2 gap-4">
  <FormField type="search" label={t.identifiers.identifier} bind:value={query.identifier.value}
             class="w-full" placeholder={t.identifiers.identifierPlaceholder} helpText={t.identifiers.identifierDescription}/>

  <MultipleSelect required={false} label={t.identifiers.identifierType} bind:values={identifierTypes} options={t.identifiers.types} emptyOption={t.identifiers.addType} class="h-auto"/>

  <SelectField required={false} label={t.identifiers.registrationCountry} bind:value={query.registrationCountryCode}
               options={CountryCode} emptyOption={t.general.any} helpText={t.identifiers.registrationCountryDescription}/>

  <SelectField required={false} label={t.identifiers.transportationMode} bind:value={query.modeCode}
               options={t.identifiers.modes} emptyOption={t.general.any} helpText={t.identifiers.transportationModeDescription}/>

  <CheckboxField label={t.identifiers.dangerousGoods} bind:checked={query.dangerousGoodsIndicator}
                 helpText={t.identifiers.dangerousGoodsDescription}/>

  <CheckboxField label={t.identifiers.forceBroadcast} bind:checked={query.forceBroadcast}
                 helpText={t.identifiers.forceBroadcastDescription}/>

  <Button type="submit" label={t.general.search} class="primary"/>
</Form>
