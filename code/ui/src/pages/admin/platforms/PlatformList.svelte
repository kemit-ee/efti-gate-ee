<script lang="ts">
  import SortableTable from 'src/components/SortableTable.svelte'
  import {formatDateTime, t} from 'i18n'
  import Button from 'src/components/Button.svelte'
  import {type Platform, Status} from "src/api/ruuterTypes";

  export let platforms: Platform[]
  export let onDetails: (platform: Platform) => void
</script>

<SortableTable items={platforms} labels={t.platforms} columns={['id', 'baseUrl', ['eDelivery', p => !!p.baseUrl], 'headers', ['apiKey', p => p.apiKeyGeneratedAt ?? ''], 'status', '']} let:item={p}>
  <tr class="{p.status === Status.DISABLED ? 'bg-neutral-300' : 'bg-none'}">
    <td>{p.id}</td>
    <td><a href={p.baseUrl} target="_blank">{p.baseUrl}</a></td>
    <td class="text-center">{p.eDeliveryCert ? '✅' : ''}</td>
    <td>{Object.keys(p.headers ?? {}).length}</td>
    <td>
      {#if p.apiKeyGeneratedAt}
        <span class="font-mono text-xs">{p.apiKeyHint}…</span>
        <span class="block text-xs text-neutral-500">{formatDateTime(p.apiKeyGeneratedAt)}</span>
      {:else}
        <span class="text-xs text-neutral-500">{t.platforms.apiKeyNone}</span>
      {/if}
    </td>
    <td>
      <div class="flex items-center gap-2">
        <div class="h-4 w-4 rounded-full {p.status === Status.ONLINE ? 'bg-success-500' : p.status === Status.DISABLED ? 'bg-warning-500' :  'bg-danger-500'}" ></div>
        <span>{(p.status && t.statuses[p.status]) ?? t.statuses[Status.OFFLINE]}</span>
      </div>
    </td>
    <td>
      <div class="flex flex-wrap justify-end gap-2">
        <Button label={t.general.details} onclick={() => onDetails(p)} size="sm"/>
      </div>
    </td>
  </tr>
</SortableTable>
