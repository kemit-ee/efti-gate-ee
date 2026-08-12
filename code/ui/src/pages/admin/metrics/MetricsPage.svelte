<script lang="ts">
  import { onMount, onDestroy } from 'svelte'
  import SortableTable from 'src/components/SortableTable.svelte'
  import {t} from 'i18n'

  let metrics = $state<{metric: string, value: string}[]>([])
  let interval: ReturnType<typeof setInterval>

  function parse(text: string) {
    return text.split('\n').flatMap(line => {
      const m = line.match(/^(\w+)(?:\{value="([^"]+)"})?\s+(\S+)$/)
      return m ? [{ metric: m[1], value: m[2] ?? m[3] }] : []
    })
  }

  async function fetchMetrics() {
    metrics = parse(await (await fetch('/metrics')).text())
  }

  onMount(() => {
    fetchMetrics()
    interval = setInterval(fetchMetrics, 10_000)
  })

  onDestroy(() => clearInterval(interval))
</script>

<div class="max-w-screen-lg mx-auto">
  <SortableTable items={metrics} labels={t.metrics} columns={['metric', 'value']} let:item={m}>
    <tr>
      <td><code>{m.metric}</code></td>
      <td>{m.value}</td>
    </tr>
  </SortableTable>
</div>
