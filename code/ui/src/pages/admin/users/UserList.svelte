<script lang="ts">
  import type {User} from 'src/api/types'
  import api from 'src/api/api'
  import {t} from 'i18n'
  import SortableTable from 'src/components/SortableTable.svelte'
  import Button from 'src/components/Button.svelte'
  import {showToast} from 'src/stores/toasts'
  import {user} from 'src/stores/auth'

  export let users: User[]
  export let onEdit: (User: User) => void
  export let onDeleted: (User: User) => void

  async function onDelete(user: User) {
    if (!confirm(t.general.deleteConfirm + ' ' + user.name + '?')) return
    await api.delete(`users/${user.id}`)
    showToast(t.general.deleted + ': ' + user.name)
    onDeleted(user)
  }
</script>

<SortableTable items={users} labels={t.users} columns={['name', 'email', 'roles', ['admin', u => u.isAdmin], 'subsets', '']} let:item={u}>
  <tr>
    <td>{u.name}</td>
    <td><a href="mailto:{u.email}">{u.email}</a></td>
    <td>{Object.entries(u.roles).map(e => e[0].toLowerCase() + ':\u00A0' + e[1]).join(', ')}</td>
    <td class="text-center">{u.isAdmin ? '✅' : ''}</td>
    <td>{u.subsets?.join(', ')}</td>
    <td>
      <Button label={t.general.edit} onclick={() => onEdit(u)} size="sm"/>
      {#if $user?.id !== u.id}
        <Button label={t.general.delete} onclick={() => onDelete(u)} size="sm" class="danger"/>
      {/if}
    </td>
  </tr>
</SortableTable>
