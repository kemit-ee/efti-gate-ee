<script lang='ts'>
  import SortableTable from 'src/components/SortableTable.svelte'
  import {formatDateTime, t} from 'i18n'
  import type {Consignment} from 'src/api/types'
  import Button from 'src/components/Button.svelte'
  import Modal from 'src/components/Modal.svelte'
  import XmlViewer from 'src/pages/authority/datasets/XmlViewer.svelte'
  import api from 'src/api/api'
  import {showToast} from 'src/stores/toasts'
  import QrCodeViewer from 'src/pages/authority/identifiers/QrCodeViewer.svelte'
  import {combineUIL} from 'src/pages/authority/uil'

  export let consignments: Consignment[] | undefined
  export let onDeleted: (consignment: Consignment) => void
  export let hasMore = false
  export let onLoadMore: (() => void) | undefined = undefined

  let showConsignment: Consignment|false = false
  let showQrCode: string | false = false

  async function onDelete(c: Consignment) {
    const id = c.uil.datasetId
    if (!confirm(t.general.deleteConfirm + ' ' + id + '?')) return
    await api.delete(`consignments/${id}`)
    showToast(t.general.deleted + ': ' + id)
    onDeleted(c)
  }
</script>

<SortableTable items={consignments} asc={-1} labels={t.consignments} {hasMore} {onLoadMore} columns={[
                 ['platformId', c => c.uil.platformId],
                 ['datasetId', c => c.uil.datasetId],
                 ['identifiers', c => c.identifiers.map(i => i.id).join(',')],
                 ['updatedAt', c => c.updatedAt], '']}
               let:item={c}>
  <tr>
    <td>{c.uil.platformId}</td>
    <td>{c.uil.datasetId}</td>
    <td>{c.identifiers.map(i => i.id).join(', ')}</td>
    <td class="text-muted">{formatDateTime(c.updatedAt)}</td>
    <td>
      <Button label={t.general.view} onclick={() => showConsignment = c}/>
      <Button
        label={t.consignments.viewQrCode}
        onclick={() => showQrCode = `${combineUIL(c.uil)}`}
      />
      <Button label={t.general.delete} onclick={() => onDelete(c)} class="danger"/>
    </td>
  </tr>
</SortableTable>

<Modal bind:show={showQrCode} title={t.consignments.uilQrCode}>
  {#if showQrCode}
    <QrCodeViewer content={showQrCode} />
  {/if}
</Modal>

<Modal bind:show={showConsignment} title="{t.consignments.consignment} {showConsignment ? showConsignment.uil.datasetId : ''}" wide>
  {#if showConsignment}
    <XmlViewer content={showConsignment.xml}/>
  {/if}
</Modal>
