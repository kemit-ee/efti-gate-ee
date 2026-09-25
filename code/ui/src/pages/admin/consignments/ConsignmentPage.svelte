<script lang="ts">
  import {t} from 'i18n'
  import {onMount} from 'svelte'
  import api from 'src/api/api'
  import ConsignmentList from 'src/pages/admin/consignments/ConsignmentList.svelte'
  import type {Consignment} from "src/api/ruuterTypes";

  const pageSize = 1000
  let consignments: Consignment[] | undefined = undefined
  let hasMore = true
  let loading = false

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
</script>

<div class="mb-6 flex justify-between items-center gap-8">
  <h1>{t.consignments.title}</h1>
</div>

<ConsignmentList consignments={consignments} {hasMore} onLoadMore={loadMore} onDeleted={resetAndLoad}/>
