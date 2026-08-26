<script lang="ts">
  import api from 'src/api/api'
  import SortableTable from 'src/components/SortableTable.svelte'
  import {showToast} from 'src/stores/toasts'
  import {formatDateTime, t} from 'i18n'
  import Button from 'src/components/Button.svelte'
  import {type Gate, Status} from 'src/api/ruuterTypes'

  export let gates: Gate[]
  export let onEdit: (gate: Gate) => void
  export let onDeleted: (gate: Gate) => void

  async function ping(gate: Gate) {
    try {
      const newGate = await api.post<Gate>(`gates/ping/${gate.id}`)
      showToast(gate.id + ' ' + t.general.pinged)
      gates = [...gates.filter(g => g.id !== gate.id), newGate]
    } catch (e: any) {
      if (gate.status !== Status.DISABLED) gates = gates.map(g => g.id === gate.id ? { ...g, status: Status.OFFLINE } : g)
      throw e
    }
  }

  async function onDelete(gate: Gate) {
    if (!confirm(t.general.deleteConfirm + ' ' + gate.id + '?')) return
    await api.delete(`gates/${gate.id}`)
    showToast(t.general.deleted + ': ' + gate.id)
    onDeleted(gate)
  }
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
        <Button label={t.general.edit} onclick={() => onEdit(g)} size="sm"/>
        <Button label={t.general.ping} onclick={() => ping(g)} size="sm"/>
        <Button label={t.general.delete} onclick={() => onDelete(g)} size="sm" class="danger"/>
      </div>
    </td>
  </tr>
</SortableTable>
