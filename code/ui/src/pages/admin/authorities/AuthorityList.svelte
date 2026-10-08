<script lang="ts">
  import {t} from 'i18n'
  import SortableTable from 'src/components/SortableTable.svelte'
  import Button from 'src/components/Button.svelte'
  import type {Authority} from 'src/api/ruuterTypes'

  export let authorities: Authority[]
  export let onDetails: (authority: Authority) => void
</script>

<SortableTable items={authorities} labels={t.authorities} columns={['id', 'name', 'registryCode', 'subsets', '']} let:item={a}>
  <tr>
    <td>{a.id}</td>
    <td>{a.name}</td>
    <td>{a.registryCode}</td>
    <td>{a.subsets.join(', ')}</td>
    <td>
      <Button label={t.general.details} onclick={() => onDetails(a)} size="sm"/>
    </td>
  </tr>
</SortableTable>
