<script lang="ts">
  import SortableTable from 'src/components/SortableTable.svelte'
  import {formatDateTime, t} from 'i18n'
  import Button from 'src/components/Button.svelte'
  import {type Gate, Status} from 'src/api/ruuterTypes'

  export let gates: Gate[]
  export let onDetails: (gate: Gate) => void
</script>

<SortableTable items={gates} labels={t.gates} columns={['id', [t.general.countryCode, 'countryCode'], 'eDeliveryUrl', 'status', '']} let:item={g}>
  <tr class="{g.status === Status.DISABLED ? 'bg-neutral-300' : 'bg-none'}">
    <td>{g.id}</td>
    <td>{g.countryCode}</td>
    <td><a href={g.eDeliveryUrl} target="_blank">{g.eDeliveryUrl}</a></td>
    <td>
      <div title={t.general.lastPingedAt + formatDateTime(g.lastPingAt)} class="flex items-center gap-2">
        <div class="h-4 w-4 rounded-full {g.status === Status.ONLINE ? 'bg-success-500' : g.status === Status.DISABLED ? 'bg-warning-500' :  'bg-danger-500'}" ></div>
        <span>{t.statuses[g.status]}</span>
      </div>
    </td>
    <td>
      <div class="flex flex-wrap justify-end gap-2">
        <Button label={t.general.details} onclick={() => onDetails(g)} size="sm"/>
      </div>
    </td>
  </tr>
</SortableTable>
