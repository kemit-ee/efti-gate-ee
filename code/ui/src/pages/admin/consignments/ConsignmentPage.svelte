<script lang="ts">
  import {t} from 'i18n'
  import {onMount} from 'svelte'
  import api from 'src/api/api'
  import ConsignmentList from 'src/pages/admin/consignments/ConsignmentList.svelte'
  import FormField from 'src/forms/FormField.svelte'
  import CheckboxField from 'src/forms/CheckboxField.svelte'
  import {debounce} from 'src/shared/debounce'
  import type {Consignment} from "src/api/ruuterTypes";

  const pageSize = 1000
  let consignments: Consignment[] | undefined = undefined
  let hasMore = true
  let loading = false
  let filter = ''
  let showOnlyDelivered = false

  onMount(loadMore)

  async function loadMore() {
    if (loading || !hasMore) return
    loading = true
    try {
      const page = await api.get<Consignment[]>(`consignments`)
      consignments = [...consignments ?? [], ...page]
      hasMore = page.length === pageSize
    } finally {
      loading = false
    }
  }

  async function resetAndLoad() {
    consignments = undefined
    hasMore = true
    loading = false
    await loadMore()
  }

  const debouncedReset = debounce(resetAndLoad)

  function onFilterInput() {
    debouncedReset()
  }

  function onDeliveredChange() {
    resetAndLoad()
  }
</script>

<div class="mb-6 flex justify-between items-center gap-8">
  <h1>{t.consignments.title}</h1>
  <div class="flex items-center gap-4">
    <CheckboxField label={t.consignments.showCabotage} bind:checked={showOnlyDelivered} onchange={onDeliveredChange}/>
    <FormField type="search" placeholder={t.consignments.filter} bind:value={filter} oninput={onFilterInput}/>
  </div>
</div>

<ConsignmentList consignments={consignments} {hasMore} onLoadMore={loadMore} onDeleted={resetAndLoad}/>
