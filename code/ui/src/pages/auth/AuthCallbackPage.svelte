<script lang="ts">
  import {onMount} from 'svelte'
  import {navigate} from 'src/router'
  import api from 'src/api/api'

  let error = $state<string | null>(null)
  let loading = $state(true)

  onMount(async () => {
    const params = new URLSearchParams(window.location.search)
    const code = params.get('code')
    const state = params.get('state')

    if (!code || !state) {
      error = 'Puudub code või state parameeter'
      loading = false
      return
    }

    try {
      await api.post('auth/callback', {code, state})
      const redirectTo = sessionStorage.getItem('authRedirectTo') || '/gates'
      sessionStorage.removeItem('authRedirectTo')
      navigate(redirectTo, {replace: true})
    } catch (e: any) {
      error = e?.message ?? 'Autentimine ebaõnnestus'
      loading = false
    }
  })
</script>

{#if loading && !error}
  <p>Autentimine käib...</p>
{:else if error}
  <p>Viga: {error}</p>
  <a href="/">Tagasi</a>
{/if}
