<script lang="ts">
  import api from 'src/api/api'
  import {t} from 'i18n'
  import Form from 'src/forms/Form.svelte'
  import Button from 'src/components/Button.svelte'
  import FormField from 'src/forms/FormField.svelte'
  import {showToast} from 'src/stores/toasts'
  import SelectField from 'src/forms/SelectField.svelte'
  import CheckboxField from 'src/forms/CheckboxField.svelte'
  import {onMount} from 'svelte'
  import type {User, CreateUserRequest, UpdateUserRequest, Gate, Platform, Authority} from "src/api/ruuterTypes";

  export let user: User
  export let onSaved = () => {}

  const isEdit = !!user.id

  let gates: Gate[] = []
  let authorities: Authority[] = []
  let selectedGateId: undefined | string = user.roles.ADMIN?.first()
  let selectedAuthorityId: undefined | string = user.roles.AUTHORITY?.first()

  onMount(async () => {
    gates = await api.get('v1/gates')
    authorities = await api.get('v1/authorities')
  })

  $: gateOptions = Object.fromEntries(gates.map(g => [g.id, g.id]))
  $: authorityOptions = Object.fromEntries(authorities.map(a => [a.id, a.id]))
  $: user.roles = {
    ...(selectedGateId ? { ADMIN: [selectedGateId] } : {}),
    ...(selectedAuthorityId ? { AUTHORITY: [selectedAuthorityId] } : {})
  }

  async function submit() {
    if (isEdit) {
      const request: UpdateUserRequest = {
        name: user.name,
        roles: user.roles,
        isActive: user.isActive,
      }
      await api.put(`v1/users/${user.id}`, request)
    } else {
      const request: CreateUserRequest = {
        taraSub: user.taraSub,
        email: user.email,
        name: user.name,
        isAdmin: user.isAdmin || false,
        roles: user.roles,
        subsets: user.subsets || []
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
  <FormField label={t.users.email} bind:value={user.email}/>
  <SelectField label={t.users.selectGate} bind:value={selectedGateId} options={gateOptions} emptyOption="--" required={false}/>
  <SelectField label={t.users.selectAuthority} bind:value={selectedAuthorityId} options={authorityOptions} emptyOption="--" required={false}/>
  {#if isEdit}
    <CheckboxField label="Active" bind:checked={user.isActive}/>
  {/if}

  <Button type="submit" label={t.general.save} class="primary"/>
</Form>