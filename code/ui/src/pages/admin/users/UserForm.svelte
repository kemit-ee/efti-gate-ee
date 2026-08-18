<script lang="ts">
  import api from 'src/api/api'
  import {t} from 'i18n'
  import Form from 'src/forms/Form.svelte'
  import Button from 'src/components/Button.svelte'
  import FormField from 'src/forms/FormField.svelte'
  import {showToast} from 'src/stores/toasts'
  import type {CreateUserRequest, UpdateUserRequest, User} from 'src/api/ruuterTypes'

  export let user: User
  export let onSaved = () => {}

  const isEdit = !!user.id

  async function submit() {
    if (isEdit) {
      const request: UpdateUserRequest = {
        name: user.name,
      }
      await api.put(`v1/users?userId=${user.id}`, request)
    } else {
      const request: CreateUserRequest = {
        taraSub: user.taraSub,
        name: user.name,
      }
      await api.post('v1/users', request)
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

  <Button type="submit" label={t.general.save} class="primary"/>
</Form>
