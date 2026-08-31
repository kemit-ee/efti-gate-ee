<script lang="ts">
  import SortableTable from 'src/components/SortableTable.svelte'
  import {t, formatDateTime} from 'i18n'
  import api from 'src/api/api'
  import {showToast} from 'src/stores/toasts'
  import Button from 'src/components/Button.svelte'
  import ApiKeyModal from 'src/pages/admin/platforms/ApiKeyModal.svelte'
  import {type Platform, type PlatformApiKey, Status} from "src/api/ruuterTypes";

  export let platforms: Platform[]
  export let onEdit: (platform: Platform) => void
  export let onDeleted: (platform: Platform) => void
  export let onChanged: () => void = () => {}

  let keyResult: PlatformApiKey | false = false

  async function onDelete(platform: Platform) {
    if (!confirm(t.general.deleteConfirm + ' ' + platform.id + '?')) return
    await api.delete(`platforms/${platform.id}`)
    showToast(t.general.deleted + ': ' + platform.id)
    onDeleted(platform)
  }

  async function generateKey(platform: Platform) {
    if (platform.hasApiKey && !confirm(t.platforms.apiKeyRegenerateConfirm)) return
    keyResult = await api.post<PlatformApiKey>(`platforms/api-key/${platform.id}`)
  }

  async function ping(platform: Platform) {
    try {
      platform = await api.post(`platforms/ping/${platform.id}`)
      platforms = platforms.replaceById(platform)
      showToast(platform.id + ' ' + t.general.pinged)
    } catch (e: any) {
      if (platform.status !== Status.DISABLED) platforms = platforms.map(g => g.id === platform.id ? { ...g, status: Status.OFFLINE } : g)
      throw e
    }
  }
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
        <Button label={t.general.edit} onclick={() => onEdit(p)} size="sm"/>
        <Button label={t.platforms.generateApiKey} onclick={() => generateKey(p)} size="sm"/>
        <Button label={t.general.ping} onclick={() => ping(p)} size="sm"/>
        <Button label={t.general.delete} onclick={() => onDelete(p)} size="sm" class="danger"/>
      </div>
    </td>
  </tr>
</SortableTable>

<ApiKeyModal bind:result={keyResult} onClose={onChanged}/>
