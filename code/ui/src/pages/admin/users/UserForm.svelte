<script lang="ts">
  import api from 'src/api/api'
  import {t} from 'i18n'
  import Form from 'src/forms/Form.svelte'
  import Button from 'src/components/Button.svelte'
  import FormField from 'src/forms/FormField.svelte'
  import {showToast} from 'src/stores/toasts'
  import {currentUser} from 'src/stores/session'
  import type {CreateUserRequest, UpdateUserRequest, User} from 'src/api/ruuterTypes'

  export let user: User
  export let onSaved = () => {}

  const isEdit = !!user.id

  // The admin cannot remove their own admin access.
  $: isSelf = !!($currentUser && user.id && $currentUser.id === user.id)

  async function submit() {
    const request: CreateUserRequest & UpdateUserRequest = {
      taraSub: user.taraSub,
      name: user.name,
      isAdmin: !!user.isAdmin,
    }
    if (isEdit) {
      await api.put(`users/${user.id}`, request)
    } else {
      await api.post('users', request)
    }

    showToast(isEdit ? t.general.saved : `${t.users.user} added`)
    onSaved()
  }
</script>

<Form {submit}>
  {#if !isEdit}
    <FormField label="TARA Sub" bind:value={user.taraSub}/>
  {/if}
  <FormField label={t.users.name} bind:value={user.name}/>

  <fieldset class="flex flex-col gap-2 mt-2">
    <legend class="text-sm font-medium mb-1">{t.users.access ?? 'Ligipääs'}</legend>
    <label class="flex items-center gap-2 text-sm"
           title={isSelf && user.isAdmin ? (t.users.cannotRemoveOwnAdmin ?? 'Ei saa enda admin-õigust eemaldada') : ''}>
      <input
        type="checkbox"
        bind:checked={user.isAdmin}
        disabled={isSelf && user.isAdmin}
      />
      {t.users.isAdmin ?? 'Admin'}
    </label>
  </fieldset>

  <Button type="submit" label={t.general.save} class="primary"/>
</Form>
