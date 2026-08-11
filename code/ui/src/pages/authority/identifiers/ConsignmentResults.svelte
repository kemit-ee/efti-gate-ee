<script lang="ts">
  import {Link} from 'src/router'
  import {t} from 'i18n'
  import {combineUIL} from 'src/pages/authority/uil'
  import type {ConsignmentXml} from 'src/api/types'
  import SortableTable from 'src/components/SortableTable.svelte'
  import QrCodeViewer from 'src/pages/authority/identifiers/QrCodeViewer.svelte'
  import Modal from 'src/components/Modal.svelte'
  import Button from 'src/components/Button.svelte'

  export let results: ConsignmentXml[]
  let showQrCode: string | false = false
</script>

<h2 class="mt-8 mb-4">{t.consignments.title}</h2>

<SortableTable items={results} columns={[
  [t.gates.id, r => r.uil?.gateId],
  [t.platforms.id, r => r.uil?.platformId],
  [t.datasets.datasetId, r => r.uil?.datasetId],
  [t.identifiers.transport, ''],
  [t.identifiers.equipment, ''],
  [t.identifiers.carrierAcceptance, r => r.carrierAcceptanceDateTime?.value],
  [t.identifiers.delivery, r => r.deliveryDateTime?.value],
]} let:item={r}>
  <tr>
    <td>{r.uil?.gateId}</td>
    <td>{r.uil?.platformId}</td>
    <td>
      {r.uil?.datasetId}
      <div class="mt-1 flex flex-wrap gap-2">
        <Link to="/dataset/{combineUIL(r.uil!)}" class="btn default">
          {t.identifiers.queryDataset}
        </Link>

        <Button label={t.consignments.viewQrCode} onclick={() => showQrCode = `${combineUIL(r.uil!)}`}/>
      </div>
    </td>
    <td>
      {#each r.mainCarriageTransportMovement as m}
        <div class="mb-4">
          <div class="font-bold">{t.identifiers.modes[m.modeCode!]}</div>
          {m.usedTransportMeans?.id.value}
          <span class="text-muted">{m.usedTransportMeans?.registrationCountry}</span>
          {#if m.dangerousGoodsIndicator}
            <div class="text-red-600">{t.identifiers.dangerousGoods}</div>
          {/if}
        </div>
      {/each}
    </td>
    <td>
      {#each r.usedTransportEquipment as e}
        <div>
          {e.id.value}
          <span class="text-muted">{e.registrationCountry}</span>
          {#if e.sequenceNumber}({e.sequenceNumber}){/if}
          {#if e.categoryCode}
            <div class="text-gray-600">{t.identifiers.category}: {e.categoryCode}</div>
          {/if}
          {#if e.carriedTransportEquipment.length > 0}
            <div>
              <div class="text-gray-600 text-xs">{t.identifiers.carriedEquipment}:</div>
              {#each e.carriedTransportEquipment as carried}
                <div>
                  {carried.id.value}
                  {#if carried.sequenceNumber}({carried.sequenceNumber}){/if}
                </div>
              {/each}
            </div>
          {/if}
        </div>
      {/each}
    </td>
    <td>{r.carrierAcceptanceDateTime?.value}</td>
    <td>{r.deliveryDateTime?.value}</td>
  </tr>
</SortableTable>

<Modal bind:show={showQrCode} title={t.consignments.uilQrCode}>
  {#if showQrCode}
    <QrCodeViewer content={showQrCode} />
  {/if}
</Modal>
