<script lang="ts">
  import {type Platform, Status} from 'src/api/types'
  import SortableTable from 'src/components/SortableTable.svelte'
  import {t} from 'i18n'
  import api from 'src/api/api'
  import {showToast} from 'src/stores/toasts'
  import Button from 'src/components/Button.svelte'
  import {navigate} from 'src/router'

  export let platforms: Platform[]
  export let onEdit: (platform: Platform) => void
  export let onDeleted: (platform: Platform) => void
  export let consignmentCounts: Record<string, number> = {}
  export let onTest: (platform: Platform) => void

  async function onDelete(platform: Platform) {
    if (!confirm(t.general.deleteConfirm + ' ' + platform.id + '?')) return
    await api.delete(`platforms/${platform.id}`)
    showToast(t.general.deleted + ': ' + platform.id)
    onDeleted(platform)
  }

  async function ping(platform: Platform) {
    try{
      await api.post(`platforms/${platform.id}/ping`)
      showToast(platform.id + ' pinged successfully')
    } catch (e: any) {
      if (!platform.isDisabled) platforms = platforms.map(g => g.id === platform.id ? { ...g, status: Status.OFFLINE, isOnline: false } : g)
      throw e
    }
  }
</script>

<SortableTable items={platforms} labels={t.platforms} columns={['id', 'baseUrl', 'supportsSubsetting', ['eDelivery', p => !!p.eDeliveryUrl], 'headers', 'status', ['consignments', p => consignmentCounts[p.id] ?? 0], '']} let:item={p}>
  <tr class="{p.isDisabled ? 'bg-neutral-300' : 'bg-none'}">
    <td>{p.id}</td>
    <td><a href={p.baseUrl} target="_blank">{p.baseUrl}</a></td>
    <td class="text-center">{p.supportsSubsetting ? '✅' : ''}</td>
    <td class="text-center">{p.eDeliveryCert ? '✅' : ''}</td>
    <td>{Object.keys(p.headers).join(', ')}</td>
    <td>
      <div class="flex items-center gap-2">
        <div class="h-4 w-4 rounded-full {p.isOnline ? 'bg-success-500' : p.isDisabled ? 'bg-warning-500' :  'bg-danger-500'}" ></div>
        <span>{t.statuses[p.status]}</span>
      </div>
    </td>
    <td class="text-center">{consignmentCounts[p.id] ?? 0}</td>
    <td>
      <div class="flex flex-wrap justify-end gap-2">
        <Button label={t.users.title} onclick={() => navigate('/users?platformId=' + p.id)} size="sm"/>
        <Button label={t.general.edit} onclick={() => onEdit(p)} size="sm"/>
        <Button label={t.general.ping} onclick={() => ping(p)} size="sm"/>
        <Button label={t.general.test} onclick={() => onTest(p)} size="sm"/>
        <Button label={t.general.delete} onclick={() => onDelete(p)} size="sm" class="danger"/>
      </div>
    </td>
  </tr>
</SortableTable>
