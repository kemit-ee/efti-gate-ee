<script lang="ts">
  import UserList from './UserList.svelte'
  import {t} from 'i18n'
  import api from 'src/api/api'
  import {Role, type User} from 'src/api/types'
  import {onMount} from 'svelte'
  import Modal from 'src/components/Modal.svelte'
  import UserForm from './UserForm.svelte'
  import Button from 'src/components/Button.svelte'
  import {navigate} from 'src/router'

  let users: User[]
  let editUser: User | false = false

  onMount(() => load())

  async function load() {
    users = await api.get('users' + location.search)
  }

  function add() {
    editUser = {roles: {}} as User
    for (let [k, v] of new URLSearchParams(location.search)) {
      if (k.endsWith('Id')) {
        const role = Role[k.replace("Id", "").toUpperCase()]
        editUser.roles[role] = [v]
      }
    }
  }

  function onSaved() {
    editUser = false
    load()
  }
</script>

<h1 class="mb-6 flex gap-8 items-center justify-between">
  <span class="flex items-center gap-2">
    {t.users.title} ({users?.length})
    {#key users?.length ?? 0}
      {#if location.search}
        <small class="text-muted">{decodeURIComponent(location.search.slice(1)).replace('Id=', ': ').replace('&', ', ')}</small>
        <Button label="×" onclick={() => {navigate(location.pathname); load()}} class="ml-2 danger" title={t.general.remove}/>
      {/if}
    {/key}
  </span>
  <Button label={t.general.add} onclick={add} class="primary"/>
</h1>

<UserList {users} onEdit={a => editUser = a} onDeleted={load}/>

<Modal bind:show={editUser} title={t.users.user}>
  {#if editUser}
    <UserForm user={editUser} {onSaved} hideRoles={!!location.search}/>
  {/if}
</Modal>
