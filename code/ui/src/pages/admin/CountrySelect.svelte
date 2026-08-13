<script lang="ts">
  import SelectField from 'src/forms/SelectField.svelte'
  import {t} from 'i18n'
  import {CountryCode} from "src/api/ruuterTypes";

  export let label = t.general.countryCode
  export let countryCode: CountryCode
</script>

<SelectField {label} bind:value={countryCode} options={CountryCode} emptyOption="" {...$$restProps}/>
