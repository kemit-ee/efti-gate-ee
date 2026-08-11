<script lang="ts">
  import {CountryCode} from 'src/api/types'
  import SelectField from 'src/forms/SelectField.svelte'
  import {t} from 'i18n'

  export let label = t.general.countryCode
  export let countryCode: CountryCode
</script>

<SelectField {label} bind:value={countryCode} options={CountryCode} emptyOption="" {...$$restProps}/>
