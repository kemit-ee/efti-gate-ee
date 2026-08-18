<script lang="ts">
  import api from 'src/api/api'
  import {t} from 'i18n'
  import SortableTable from 'src/components/SortableTable.svelte'
  import Button from 'src/components/Button.svelte'
  import {showToast} from 'src/stores/toasts'
  import type {User} from "src/api/ruuterTypes";

  export let users: User[]
  export let onEdit: (user: User) => void
  export let onDeleted: (user: User) => void

  async function onDelete(user: User) {
    if (!confirm(t.general.deleteConfirm + ' ' + user.id + '?')) return
    await api.delete(`v1/users/${user.id}`)
    showToast(t.general.deleted + ': ' + user.id)
    onDeleted(user)
  }
</script>

<SortableTable items={users} labels={t.users} columns={['id', 'name', 'taraSub', 'roles', 'gateId', 'platformId', 'authorityId', 'isActive', '']} let:item={u}>
  <tr>
    <td>{u.id}</td>
    <td>{u.name}</td>
    <td>{u.taraSub}</td>
    <td>{u.roles.join(', ')}</td>
    <td>{u.gateId || '-'}</td>
    <td>{u.platformId || '-'}</td>
    <td>{u.authorityId || '-'}</td>
    <td>{u.isActive ? 'Yes' : 'No'}</td>
    <td>
      <Button label={t.general.edit} onclick={() => onEdit(u)} size="sm"/>
      <Button label={t.general.delete} onclick={() => onDelete(u)} size="sm" class="danger"/>
    </td>
  </tr>
</SortableTable>