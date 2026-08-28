<script lang="ts">
  import api from 'src/api/api'
  import {t} from 'i18n'
  import Form from 'src/forms/Form.svelte'
  import Button from 'src/components/Button.svelte'
  import FormField from 'src/forms/FormField.svelte'
  import {showToast} from 'src/stores/toasts'
  import {currentUser} from 'src/stores/session'
  import type {CreateUserRequest, UpdateUserRequest, User} from 'src/api/ruuterTypes'
  import {UserRole} from 'src/api/ruuterTypes'

  export let user: User
  export let onSaved = () => {}

  const isEdit = !!user.id

  // The admin cannot remove their own ADMIN role.
  $: isSelf = !!($currentUser && user.id && $currentUser.id === user.id)
  $: hasAdmin = user.roles?.includes(UserRole.ADMIN) ?? false
  $: hasAuthority = user.roles?.includes(UserRole.AUTHORITY) ?? false

  function toggleRole(role: UserRole, checked: boolean) {
    const current = user.roles ?? []
    if (checked) {
      user.roles = [...new Set([...current, role])]
    } else {
      user.roles = current.filter(r => r !== role)
    }
  }

  async function submit() {
    if (isEdit) {
      const request: UpdateUserRequest = {
        taraSub: user.taraSub,
        name: user.name,
        roles: user.roles ?? [],
      }
      await api.put(`users/${user.id}`, request)
    } else {
      const request: CreateUserRequest = {
        taraSub: user.taraSub,
        name: user.name,
        roles: user.roles ?? [],
      }
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
    <legend class="text-sm font-medium mb-1">{t.users.roles ?? 'Rollid'}</legend>
    <label class="flex items-center gap-2 text-sm"
           title={isSelf && hasAdmin ? (t.users.cannotRemoveOwnAdmin ?? 'Ei saa enda ADMIN rolli eemaldada') : ''}>
      <input
        type="checkbox"
        checked={hasAdmin}
        disabled={isSelf && hasAdmin}
        on:change={e => toggleRole(UserRole.ADMIN, e.currentTarget.checked)}
      />
      Admin
    </label>
    <label class="flex items-center gap-2 text-sm">
      <input
        type="checkbox"
        checked={hasAuthority}
        on:change={e => toggleRole(UserRole.AUTHORITY, e.currentTarget.checked)}
      />
      Authority
    </label>
  </fieldset>

  <Button type="submit" label={t.general.save} class="primary"/>
</Form>
