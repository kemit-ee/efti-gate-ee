<script lang="ts">
  import {onMount} from 'svelte'
  import {navigate} from 'src/router'
  import api, {setToken} from 'src/api/api'
  import {t} from "i18n";

  let error: string | null = null
  let loading = true

  onMount(async () => {
    const params = new URLSearchParams(window.location.search)
    const code = params.get('code')
    const state = params.get('state')

    if (!code || !state) {
      error = t.auth.missingCodeOrState
      loading = false
      return
    }

    try {
      const data = await api.post<{token: string}>('/auth/callback', {code, state})
      setToken(data.token)
      navigate('/gates', {replace: true})
    } catch (e: any) {
      error = e?.message ?? t.auth.unsuccessful
      loading = false
    }
  })
</script>

{#if loading && !error}
  <p>{t.auth.authenticating}...</p>
{:else if error}
  <p>{t.general.error}: {error}</p>
  <a href="/">{t.general.back}</a>
{/if}
