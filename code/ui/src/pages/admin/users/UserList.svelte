<script lang="ts">
  import api from 'src/api/api'
  import {formatDateTime, t} from 'i18n'
  import SortableTable from 'src/components/SortableTable.svelte'
  import Button from 'src/components/Button.svelte'
  import {showToast} from 'src/stores/toasts'
  import type {User} from 'src/api/ruuterTypes'

  export let users: User[]
  export let onEdit: (user: User) => void
  export let load: () => void

  async function onDelete(user: User) {
    if (!confirm(t.general.deleteConfirm + ' ' + user.id + '?')) return
    await api.delete(`v1/users/${user.id}`)
    showToast(t.general.deleted + ': ' + user.id)
    load()
  }

  async function onRevokeToken(user: User) {
    if (!confirm(t.general.revokeToken + ' ' + user.id + '?')) return
    await api.post(`v1/users/revoke-token?userId=${user.id}`)
    showToast(t.general.revokeToken + ': ' + user.id)
    load()
  }
</script>

<SortableTable items={users} labels={t.users} columns={['id', 'name', 'taraSub', 'tokenRevokedAt', '']} let:item={u}>
  <tr>
    <td>{u.id}</td>
    <td>{u.name}</td>
    <td>{u.taraSub}</td>
    <td>{formatDateTime(u.tokenRevokedAt)}</td>
    <td>
      <Button label={t.general.edit} onclick={() => onEdit(u)} size="sm"/>
      <Button label={t.general.delete} onclick={() => onDelete(u)} size="sm" class="danger"/>
      <Button label={t.general.revokeToken} onclick={() => onRevokeToken(u)} size="sm" class="danger-neutral"/>
    </td>
  </tr>
</SortableTable>
