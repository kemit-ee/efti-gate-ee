<script lang="ts">
  import AuthorityList from 'src/pages/admin/authorities/AuthorityList.svelte'
  import {t} from 'i18n'
  import api from 'src/api/api'
  import {onMount} from 'svelte'
  import Modal from 'src/components/Modal.svelte'
  import AuthorityForm from 'src/pages/admin/authorities/AuthorityForm.svelte'
  import Button from 'src/components/Button.svelte'
  import {showToast, ToastType} from 'src/stores/toasts'
  import {navigate} from 'src/router'
  import {user} from 'src/stores/auth'
  import type {Authority, Subset} from "src/api/ruuterTypes";

  let authorities: Authority[]
  let editAuthority: Authority | false = false

  onMount(load)

  async function load() {
    authorities = await api.get('v1/authorities')
  }

  function add() {
    editAuthority = {subsets: [] as Subset[]} as Authority
  }

  function onSaved(authority: Authority, isNew: boolean) {
    editAuthority = false
    if (isNew) {
      showToast(t.users.createForAdmin, {type: ToastType.INFO, timeoutSec: 10})
      navigate(`/users?authorityId=${authority.id}`)
    } else load()
  }
</script>

<h1 class="flex justify-between items-center gap-8 mb-6">
  {t.authorities.title} ({authorities?.length})
  <Button label={t.general.add} onclick={add} class="primary"/>
</h1>

<AuthorityList {authorities} onEdit={a => editAuthority = a} onDeleted={load}/>

<Modal bind:show={editAuthority} title={t.authorities.authority}>
  {#if editAuthority}
    <AuthorityForm authority={editAuthority} {onSaved}/>
  {/if}
</Modal>
