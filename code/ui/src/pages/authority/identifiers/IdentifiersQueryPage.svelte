<script lang="ts">
  import {eftiApi} from 'src/api/api'
  import type {ConsignmentXml, GateIdentifiersResponse, IdentifiersQuery} from 'src/api/types'
  import IdentifiersSearchForm from './IdentifiersSearchForm.svelte'
  import {t} from 'i18n'
  import {onDestroy} from 'svelte'
  import Spinner from 'src/components/Spinner.svelte'
  import ConsignmentResults from 'src/pages/authority/identifiers/ConsignmentResults.svelte'

  let query: IdentifiersQuery = {identifier: {}} as IdentifiersQuery
  let loading = false
  let source: EventSource | undefined
  let gates: GateIdentifiersResponse[] = []
  let results: ConsignmentXml[] | undefined

  async function submit() {
    loading = true
    results = undefined
    gates = []
    source?.close()
    const params = new URLSearchParams(Object.entries(query).filter(p => p[0] !== 'identifier' && p[1]))
    for (const type of query.identifier.types ?? [])
      params.append('identifierTypes', type)
    source = new EventSource(eftiApi.prefix + `identifiers/${encodeURIComponent(query.identifier.value)}?${params}`)
    source.addEventListener('message', e => {
      const data = JSON.parse(e.data) as ConsignmentXml
      results = [...results ?? [], data]
    })
    source.addEventListener('gate', e => {
      const data = JSON.parse(e.data) as GateIdentifiersResponse
      gates = [...gates, data]
    })
    source.addEventListener('complete', close)
  }

  function close() {
    source?.close()
    loading = false
    if (!results) results = []
  }

  onDestroy(close)
</script>

<h1 class="text-2xl font-bold text-gray-900">{t.identifiers.identifierSearch}</h1>
<p class="text-gray-500 mt-1 text-sm mb-6">{t.identifiers.identifierSearchDescription}</p>

<div class="max-w-3xl mb-6 bg-gray-100 rounded p-4">
  <IdentifiersSearchForm bind:query {submit}/>
</div>

<div class="flex flex-wrap items-center gap-2">
  {#each gates as gate}
    <div class="rounded text-sm p-2 {gate.failure ? 'bg-red-400' : 'bg-green-400'}" title={gate.failure}>
      <b>{gate.gateId}</b>: {gate.responseTimeMs}ms ({results?.filter(r => r.uil?.gateId === gate.gateId).length ?? 0})
    </div>
  {/each}
  {#if loading}
    <div><Spinner/></div>
  {/if}
</div>

{#if results}
  <ConsignmentResults {results}/>
{/if}
