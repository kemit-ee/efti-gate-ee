<script lang='ts'>
  import SortableTable from 'src/components/SortableTable.svelte'
  import {formatDateTime, t} from 'i18n'
  import Button from 'src/components/Button.svelte'
  import Modal from 'src/components/Modal.svelte'
  import api from 'src/api/api'
  import {showToast} from 'src/stores/toasts'
  import type {Consignment} from "src/api/ruuterTypes";
  import QrCodeViewer from "src/components/QrCodeViewer.svelte";
  import XmlViewer from "src/components/XmlViewer.svelte";
  import {combineUIL} from "src/shared/uil";
  import DatasetViewer from "src/pages/admin/consignments/DatasetViewer.svelte";
  import {parseXmlToJson} from "src/shared/xmlParser";

  export let consignments: Consignment[] | undefined
  export let onDeleted: (consignment: Consignment) => void
  export let hasMore = false
  export let onLoadMore: (() => void) | undefined = undefined

  let showConsignment: Consignment|false = false
  let showQrCode: string | false = false
  let consignmentViewerType = 'ui'

  async function onDelete(c: Consignment) {
    const id = c.datasetId
    if (!confirm(t.general.deleteConfirm + ' ' + id + '?')) return
    await api.delete(`consignments/${id}`)
    showToast(t.general.deleted + ': ' + id)
    onDeleted(c)
  }
</script>

<SortableTable items={consignments} asc={-1} labels={t.consignments} {hasMore} {onLoadMore} columns={[
                 ['platformId', c => c.platformId],
                 ['datasetId', c => c.datasetId],
                 ['identifiers', c => [...c.carriedEquipmentIds, ...c.usedEquipmentIds, c.mainTransportId]],
                 ['createdAt', c => c.createdAt], '']}
               let:item={c}>
  <tr>
    <td>{c.platformId}</td>
    <td>{c.datasetId}</td>
    <td>{[...c.carriedEquipmentIds, ...c.usedEquipmentIds, c.mainTransportId].join(',')}</td>
    <td class="text-muted">{formatDateTime(c.createdAt)}</td>
    <td>
      <Button label={t.general.view} onclick={() => showConsignment = c}/>
      <Button
        label={t.consignments.viewQrCode}
        onclick={() => showQrCode = `${combineUIL(c.gateId, c.platformId, c.datasetId)}`}
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

<Modal bind:show={showConsignment} title="{t.consignments.consignment} {showConsignment ? showConsignment.datasetId : ''}" wide>
  {#if showConsignment}
    <div class="flex gap-2 mb-4">
      <Button label={t.consignments.ui} onclick={() => consignmentViewerType = 'ui'} class={consignmentViewerType === 'ui' ? 'primary' : 'secondary'}/>
      <Button label={t.consignments.xml} onclick={() => consignmentViewerType = 'xml'} class={consignmentViewerType === 'xml' ? 'primary' : 'secondary'}/>
    </div>
    <div>
      {#if consignmentViewerType === 'xml'}
        <XmlViewer content={showConsignment.xml}/>
      {:else}
        <DatasetViewer data={parseXmlToJson(showConsignment.xml)}/>
      {/if}
    </div>
  {/if}
</Modal>
