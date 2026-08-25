<script lang="ts">
  import {onMount} from 'svelte'

  onMount(async () => {
    const res = await fetch('/tim/auth/login/tara')
    const data = await res.json()
    const url = data.authorization_url.replace('https://tara-mock:8080', '/tara')
    window.location.href = url
  })
</script>

<p>Autentimine käib...</p>
