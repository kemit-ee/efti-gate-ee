<script lang="ts">
  import XmlViewer from 'src/pages/authority/datasets/XmlViewer.svelte'
  import DatasetViewer from 'src/pages/authority/datasets/DatasetViewer.svelte'
  import api, {eftiApi} from 'src/api/api'
  import {showToast} from 'src/stores/toasts'
  import {t} from 'i18n'
  import Form from 'src/forms/Form.svelte'
  import FormField from 'src/forms/FormField.svelte'
  import Button from 'src/components/Button.svelte'
  import {navigate} from 'src/router'
  import {combineUIL, parseUIL} from 'src/pages/authority/uil'
  import {type Authority, Role, type UIL} from 'src/api/types'
  import {parseXmlToJson} from 'src/shared/xmlParser'
  import {user} from 'src/stores/auth'
  import QrScanner from 'src/pages/authority/datasets/QrScanner.svelte'
  import Modal from 'src/components/Modal.svelte'

  export let uilPath = ''

  let uil = {} as UIL
  $: uil = parseUIL(uilPath)
  let subsets: string | undefined = undefined

  let xmlContent = ''
  let datasetRequestId = ''
  let viewMode: 'ui' | 'xml' = 'ui'
  let showScanner = false;

  function handleQrScan(scannedText: string) {
    const parts = scannedText.split('/');

    if (parts.length === 3) {
      uil.gateId = parts[0].trim();
      uil.platformId = parts[1].trim();
      uil.datasetId = parts[2].trim();
      uil = { ...uil };
      showScanner = false;
      submit();
    } else {
      alert("Invalid QR Code structure. Expected format: 'gateId/platformId/datasetId'");
    }
  }

  $: jsonData = xmlContent ? parseXmlToJson(xmlContent) : null

  $: if ($user !== undefined) {
    initialize()
  }

  async function submit() {
    if(!subsets) return
    uilPath = combineUIL(uil)
    navigate(`/dataset/${uilPath}`, {replace: true})

    datasetRequestId = crypto.randomUUID()
    const subsetParams = subsets.split(',').map(s => `subsetId=${s.trim()}`).join('&')
    const encodedUilPath = uilPath.split('/').map(encodeURIComponent).join('/')
    xmlContent = await eftiApi.requestXml(`dataset/${encodedUilPath}?${subsetParams}`, {
      headers: {'X-Request-ID': datasetRequestId}
    })
  }

  async function followup() {
    const message = prompt('Follow-up Message to send')
    if (!message) return

    await eftiApi.post(`follow-up/${uilPath}/${datasetRequestId}`, message, {
      'X-Request-ID': crypto.randomUUID()
    })
    showToast(t.datasets.followupSuccess)
  }

  async function initialize() {
    const authorityId = $user?.roles?.[Role.AUTHORITY]?.[0]
    if (authorityId) {
      if ($user?.subsets?.length) {
        subsets = $user.subsets[0]
      } else {
        const auth = await api.get<Authority>(`authorities/${authorityId}`)
        subsets = auth?.subsets?.[0] ?? 'EU01'
      }
    } else {
      subsets = 'EU01'
    }
    if (uilPath) await submit()
  }
</script>

<Form {submit} class="bg-gray-100 rounded p-4">
  <div class="flex flex-col items-center gap-4">
    <div class="flex flex-col md:flex-row gap-4">
      <FormField label={t.datasets.gateId} bind:value={uil.gateId}/>
      <FormField label={t.datasets.platformId} bind:value={uil.platformId}/>
      <FormField label={t.datasets.datasetId} minlength={36} maxlength={36} bind:value={uil.datasetId} class="w-72"/>
      <FormField label={t.datasets.subsets} bind:value={subsets} helpText="Comma separated subset ids" />
    </div>
    <div class="flex gap-14">
      <Button type="submit" label={t.identifiers.queryDataset} class="primary"/>
      <Button label={t.datasets.scanQrCode} onclick={() => showScanner = true} class="primary"/>
      <Button label={t.datasets.followUp} onclick={followup} class="primary" disabled={!xmlContent.length}/>
    </div>
  </div>
</Form>

{#if xmlContent}
  <div class="flex flex-col gap-4 mt-8 max-w-6xl mx-auto w-full">
    <div class="flex justify-end gap-2 px-4">
      <Button label="UI" onclick={() => viewMode = 'ui'} class={viewMode === 'ui' ? 'primary' : 'default'} size="sm"/>
      <Button label="XML" onclick={() => viewMode = 'xml'} class={viewMode === 'xml' ? 'primary' : 'default'} size="sm"/>
    </div>

    {#if viewMode === 'ui'}
      <div class="bg-white rounded-xl shadow-sm border border-gray-100 p-8 mx-4 overflow-hidden">
        <DatasetViewer data={jsonData}/>
      </div>
    {:else}
      <XmlViewer content={xmlContent} class="p-4 rounded-xl border border-gray-100 mx-4"/>
    {/if}
  </div>
{/if}

<Modal bind:show={showScanner} title={t.datasets.scanConsignmentQr}>
  {#if showScanner}
    <QrScanner onScan={handleQrScan} onClose={() => showScanner = false} />
  {/if}
</Modal>
