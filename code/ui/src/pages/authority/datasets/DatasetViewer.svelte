<script lang="ts">
  export let data: any
  export let level = 0

  function formatKey(key: string) {
    if (key === 'value') return ''
    return key
      .replace(/([A-Z])/g, ' $1')
      .replace(/^./, (str) => str.toUpperCase())
      .trim()
  }

  $: isObject = data && typeof data === 'object' && !Array.isArray(data)
  $: isArray = Array.isArray(data)

  $: isAmount = isObject && data.value !== undefined && (data.currencyId || data.unitId)
  $: isId = isObject && data.value !== undefined && (data.schemeAgencyId || data.formatId)

  $: entries = isObject ? Object.entries(data) : []

  // Check if all children are simple values to render them in a grid
  $: isSimpleObject = isObject && !isAmount && !isId && Object.values(data).every(v => typeof v !== 'object' || v === null)
</script>

{#if isAmount || isId}
  <span class="text-sm text-gray-900 bg-gray-50 px-2 py-1 rounded inline-block min-h-[1.5rem] break-all">
    {data.value}
    {#if data.currencyId || data.unitId}
      {data.currencyId || data.unitId}
    {:else if data.schemeAgencyId}
      <span class="text-xs text-muted font-mono ml-1">({data.schemeAgencyId})</span>
    {:else if data.formatId}
      <span class="text-xs text-muted font-mono ml-1">[{data.formatId}]</span>
    {/if}
  </span>
{:else if isObject}
  <div class="flex flex-col gap-3 {level > 0 ? 'ml-4 border-l border-gray-200 pl-4 my-2' : ''}">
    {#if isSimpleObject}
      <div class="grid grid-cols-1 sm:grid-cols-2 md:grid-cols-3 gap-4">
        {#each entries as [key, value]}
          <div class="flex flex-col">
            <span class="text-xs font-bold text-muted uppercase tracking-widest">{formatKey(key)}</span>
            <span class="text-sm text-gray-800 break-all">{value}</span>
          </div>
        {/each}
      </div>
    {:else}
      {#each entries as [key, value]}
        <div class="flex flex-col">
          {#if key !== 'value'}
            <span class="text-xs font-bold text-muted uppercase tracking-widest mb-1">{formatKey(key)}</span>
          {/if}
          <svelte:self data={value} level={level + 1} />
        </div>
      {/each}
    {/if}
  </div>
{:else if isArray}
  <div class="flex flex-col gap-6 {level > 0 ? 'ml-4 border-l border-gray-200 pl-4 my-4' : ''}">
    {#each data as item, i}
      <div class="relative group">
        <div class="absolute -left-[17px] top-0 bottom-0 w-[2px] bg-primary-500 opacity-0 group-hover:opacity-100 transition-opacity"></div>
        <div class="flex items-center gap-2 mb-2">
          <span class="text-xs font-black text-white bg-primary-500 rounded-full w-4 h-4 flex items-center justify-center shrink-0">{i + 1}</span>
          <div class="h-[1px] bg-gray-100 grow"></div>
        </div>
        <svelte:self data={item} level={level + 1} />
      </div>
    {/each}
  </div>
{:else}
  <span class="text-sm text-gray-900 bg-gray-50 px-2 py-1 rounded inline-block min-h-[1.5rem] break-all">
    {data}
  </span>
{/if}
