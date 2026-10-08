<script lang="ts">
  import AuthorityList from 'src/pages/admin/authorities/AuthorityList.svelte'
  import {t} from 'i18n'
  import api from 'src/api/api'
  import {onMount} from 'svelte'
  import Modal from 'src/components/Modal.svelte'
  import AuthorityForm from 'src/pages/admin/authorities/AuthorityForm.svelte'
  import type {Authority} from "src/api/ruuterTypes";

  let authorities: Authority[]
  let detailsAuthority: Authority | false = false

  onMount(load)

  async function load() {
    authorities = await api.get('authorities')
  }
</script>

<h1 class="flex justify-between items-center gap-8 mb-6">
  {t.authorities.title} ({authorities?.length})
</h1>

<AuthorityList {authorities} onDetails={a => detailsAuthority = a}/>

<Modal bind:show={detailsAuthority} title={t.authorities.authority}>
  {#if detailsAuthority}
    <AuthorityForm authority={detailsAuthority} disabled/>
  {/if}
</Modal>
