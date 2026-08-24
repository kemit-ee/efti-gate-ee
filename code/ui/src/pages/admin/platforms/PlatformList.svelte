<script lang="ts">
  import SortableTable from 'src/components/SortableTable.svelte'
  import {t} from 'i18n'
  import api from 'src/api/api'
  import {showToast} from 'src/stores/toasts'
  import Button from 'src/components/Button.svelte'
  import {navigate} from 'src/router'
  import {type Platform, Status} from "src/api/ruuterTypes";

  export let platforms: Platform[]
  export let onEdit: (platform: Platform) => void
  export let onDeleted: (platform: Platform) => void

  async function onDelete(platform: Platform) {
    if (!confirm(t.general.deleteConfirm + ' ' + platform.id + '?')) return
    await api.delete(`v1/platforms/${platform.id}`)
    showToast(t.general.deleted + ': ' + platform.id)
    onDeleted(platform)
  }

  async function ping(platform: Platform) {
    try{
      await api.post(`v1/platforms/ping/${platform.id}`)
      showToast(platform.id + ' pinged successfully')
    } catch (e: any) {
      if (platform.status !== Status.DISABLED) platforms = platforms.map(g => g.id === platform.id ? { ...g, status: Status.OFFLINE } : g)
      throw e
    }
  }
</script>

<SortableTable items={platforms} labels={t.platforms} columns={['id', 'baseUrl', ['eDelivery', p => !!p.baseUrl], 'headers', 'status', '']} let:item={p}>
  <tr class="{p.status === Status.DISABLED ? 'bg-neutral-300' : 'bg-none'}">
    <td>{p.id}</td>
    <td><a href={p.baseUrl} target="_blank">{p.baseUrl}</a></td>
    <td class="text-center">{p.eDeliveryCert ? '✅' : ''}</td>
    <td>{Object.keys(p.headers ?? {}).length}</td>
    <td>
      <div class="flex items-center gap-2">
        <div class="h-4 w-4 rounded-full {p.status === Status.ONLINE ? 'bg-success-500' : p.status === Status.DISABLED ? 'bg-warning-500' :  'bg-danger-500'}" ></div>
        <span>{(p.status && t.statuses[p.status]) ?? t.statuses[Status.OFFLINE]}</span>
      </div>
    </td>
    <td>
      <div class="flex flex-wrap justify-end gap-2">
        <Button label={t.general.edit} onclick={() => onEdit(p)} size="sm"/>
        <Button label={t.general.ping} onclick={() => ping(p)} size="sm"/>
        <Button label={t.general.delete} onclick={() => onDelete(p)} size="sm" class="danger"/>
      </div>
    </td>
  </tr>
</SortableTable>
