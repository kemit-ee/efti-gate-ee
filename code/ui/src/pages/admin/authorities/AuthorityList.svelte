<script lang="ts">
  import api from 'src/api/api'
  import {t} from 'i18n'
  import SortableTable from 'src/components/SortableTable.svelte'
  import Button from 'src/components/Button.svelte'
  import {showToast} from 'src/stores/toasts'
  import {navigate} from 'src/router'
  import type {Authority} from "src/api/ruuterTypes";

  export let authorities: Authority[]
  export let onEdit: (authority: Authority) => void
  export let onDeleted: (authority: Authority) => void

  async function onDelete(authority: Authority) {
    if (!confirm(t.general.deleteConfirm + ' ' + authority.id + '?')) return
    await api.delete(`v1/authorities/delete?authorityId=${authority.id}`)
    showToast(t.general.deleted + ': ' + authority.id)
    onDeleted(authority)
  }
</script>

<SortableTable items={authorities} labels={t.authorities} columns={['id', 'name', [t.general.countryCode, 'countryCode'], 'subsets', '']} let:item={a}>
  <tr>
    <td>{a.id}</td>
    <td>{a.name}</td>
    <td>{a.countryCode}</td>
    <td>{a.subsets.join(', ')}</td>
    <td>
      <Button label={t.general.edit} onclick={() => onEdit(a)} size="sm"/>
      <Button label={t.general.delete} onclick={() => onDelete(a)} size="sm" class="danger"/>
    </td>
  </tr>
</SortableTable>
