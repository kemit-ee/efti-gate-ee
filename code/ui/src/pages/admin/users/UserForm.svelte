<script lang="ts">
  import api from 'src/api/api'
  import {t} from 'i18n'
  import Form from 'src/forms/Form.svelte'
  import Button from 'src/components/Button.svelte'
  import FormField from 'src/forms/FormField.svelte'
  import {showToast} from 'src/stores/toasts'
  import CheckboxField from 'src/forms/CheckboxField.svelte'
  import {onMount} from 'svelte'
  import { user as currentUser } from 'src/stores/auth'
  import SelectField from 'src/forms/SelectField.svelte'
  import type {Authority, Gate, Platform, User} from "src/api/ruuterTypes";

  export let user: User
  export let hideRoles = false

  let generateSecret = !user.id
  export let onSaved = (user: User) => {}

  let newGateId = ''
  let newPlatformId = ''
  let newAuthorityId = ''

  let gates: Gate[] = []
  let platforms: Platform[] = []
  let authorities: Authority[] = []

  function addRole(role: Role, id: string): string {
    id = id.trim()
    if (!id) return id
    const current = user.roles[role] ?? []
    if (!current.includes(id)) {
      user = { ...user, roles: { ...user.roles, [role]: [...current, id] } }
    }
    if (role === Role.AUTHORITY) refreshSubsets()
    return ''
  }

  function removeRole(role: Role, id: string) {
    const updated = (user.roles[role] ?? []).filter(r => r !== id)
    const roles = { ...user.roles }
    if (updated.length === 0) delete roles[role]
    else roles[role] = updated
    user = { ...user, roles }
    if (role === Role.AUTHORITY) refreshSubsets()
  }

  async function submit() {
    const secret = await api.post<string>('users' + (generateSecret ? '?generateSecret' : ''), user)
    if (secret) prompt(user.email ? t.users.copyPastePassword : t.users.copyPasteToken, secret)
    else showToast(t.general.saved)
    onSaved(user)
  }

  let subsets: Subset[] = []
  async function refreshSubsets() {
    const authorityIds = user.roles[Role.AUTHORITY] ?? []
    if (authorityIds.length) {
      subsets = [...new Set(authorities.filter(a => authorityIds.includes(a.id)).flatMap(a => a.subsets))]
      user = { ...user, subsets: (user.subsets ?? []).filter(s => subsets.includes(s)) }
    } else {
      subsets = []
      user = { ...user, subsets: [] }
    }
  }

  onMount(async () => {
    if ($currentUser?.isSuperAdmin) {
      [gates, platforms, authorities] = await Promise.all([
        api.get<Gate[]>('gates'),
        api.get<Platform[]>('platforms'),
        api.get<Authority[]>('authorities'),
      ])
    }
    await refreshSubsets()
  })
</script>

<Form {submit}>
  <FormField label={t.users.name} bind:value={user.name}/>
  <FormField type="email" label={t.users.email} bind:value={user.email} required={false}/>
  <CheckboxField label={t.users.isAdmin} bind:checked={user.isAdmin}/>
  {#if $currentUser?.isSuperAdmin && !hideRoles}
    <div class="flex flex-col gap-2">
      <div class="flex items-end gap-2">
        <SelectField
          label={t.users.addGates} options={Object.fromEntries(gates.filter(g => !user.roles[Role.GATE]?.includes(g.id)).map(g => [g.id, g.id]))}
          bind:value={newGateId} emptyOption={t.users.selectGate} required={false} class="flex-1"/>
        <Button type="button" class="default min-w-28" label={t.users.addGate} onclick={() => { newGateId = addRole(Role.GATE, newGateId) }}/>
      </div>
      {#if user.roles[Role.GATE]?.length}
        <div class="flex flex-wrap gap-2">
          {#each user.roles[Role.GATE] as id}
        <span class="flex items-center gap-1 rounded bg-neutral-300 px-2 py-1 text-sm">
          {id}
          <Button type="button" icon="x" size="sm" data-id={id} class="text-neutral-500 hover:text-danger-600" onclick={() => removeRole(Role.GATE, id)}/>
        </span>
          {/each}
        </div>
      {/if}
    </div>

    <div class="flex flex-col gap-2">
      <div class="flex items-end gap-2">
        <SelectField
          label={t.users.addPlatforms} options={Object.fromEntries(platforms.filter(p => !user.roles[Role.PLATFORM]?.includes(p.id)).map(p => [p.id, p.id]))}
          bind:value={newPlatformId} emptyOption={t.users.selectPlatform} required={false} class="flex-1"/>
        <Button type="button" label={t.users.addPlatform} onclick={() => { newPlatformId = addRole(Role.PLATFORM, newPlatformId) }}/>
      </div>
      {#if user.roles[Role.PLATFORM]?.length}
        <div class="flex flex-wrap gap-2">
          {#each user.roles[Role.PLATFORM] as id}
        <span class="flex items-center gap-1 rounded bg-neutral-300 px-2 py-1 text-sm">
          {id}
          <Button type="button" icon="x" size="sm" data-id={id} class="text-neutral-500 hover:text-danger-600" onclick={() => removeRole(Role.PLATFORM, id)}/>
        </span>
          {/each}
        </div>
      {/if}
    </div>

    <div class="flex flex-col gap-2">
      <div class="flex items-end gap-2">
        <SelectField
          label={t.users.addAuthorities} options={Object.fromEntries(authorities.filter(a => !user.roles[Role.AUTHORITY]?.includes(a.id)).map(a => [a.id, a.id]))}
          bind:value={newAuthorityId} emptyOption={t.users.selectAuthority} required={false} class="flex-1"/>
        <Button type="button" label={t.users.addAuthority} onclick={() => { newAuthorityId = addRole(Role.AUTHORITY, newAuthorityId) }}/>
      </div>
      {#if user.roles[Role.AUTHORITY]?.length}
        <div class="flex flex-wrap gap-2">
          {#each user.roles[Role.AUTHORITY] as id}
        <span class="flex items-center gap-1 rounded bg-neutral-300 px-2 py-1 text-sm">
          {id}
          <Button type="button" icon="x" size="sm" data-id={id} class="text-neutral-500 hover:text-danger-600" onclick={() => removeRole(Role.AUTHORITY, id)}/>
        </span>
          {/each}
        </div>
      {/if}
    </div>
    {/if}

  {#if subsets.length}
    <div>
      <label class="mb-2">
        {t.users.subsets}
        <small class="text-muted font-normal">{t.users.subsetsNone}</small>
      </label>
      <div class="flex flex-wrap gap-5">
        {#each subsets as subset}
          <label class="flex items-center gap-2 font-normal">
            <input type="checkbox" bind:group={user.subsets} value={subset}>
            {subset}
          </label>
        {/each}
      </div>
    </div>
  {/if}

  <div class="flex gap-4 items-center justify-between">
    <Button type="submit" label={t.general.save} class="primary"/>
    {#if user.id}
      <CheckboxField label={t.users.generateSecret} bind:checked={generateSecret}/>
    {/if}
  </div>
</Form>
