<script lang="ts">
  import UserList from 'src/pages/admin/users/UserList.svelte'
  import {t} from 'i18n'
  import api from 'src/api/api'
  import {onMount} from 'svelte'
  import Modal from 'src/components/Modal.svelte'
  import UserForm from 'src/pages/admin/users/UserForm.svelte'
  import Button from 'src/components/Button.svelte'
  import type {User} from "src/api/ruuterTypes";

  let users: User[]
  let editUser: User | false = false

  onMount(load)

  async function load() {
    users = await api.get('v1/users')
  }

  function add() {
    editUser = {roles: {}} as User
  }

  function onSaved() {
    editUser = false
    load()
  }
</script>

<h1 class="flex justify-between items-center gap-8 mb-6">
  {t.users.title} ({users?.length})
  <Button label={t.general.add} onclick={add} class="primary"/>
</h1>

<UserList {users} onEdit={u => editUser = u} onDeleted={load}/>

<Modal bind:show={editUser} title={t.users.user}>
  {#if editUser}
    <UserForm user={editUser} {onSaved}/>
  {/if}
</Modal>