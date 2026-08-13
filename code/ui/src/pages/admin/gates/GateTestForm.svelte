<script lang="ts">
  import type {Gate} from 'src/api/types'
  import api, {eftiApi} from 'src/api/api'
  import FormField from 'src/forms/FormField.svelte'
  import Button from 'src/components/Button.svelte'
  import {t} from 'i18n'

  export let gate: Gate

  let identifier = ''
  let consignments: any[] = []

  let identifierStatus: 'running' | 'success' | 'failed' | '' = ''
  let identifierError = ''
  let identifierDuration = 0

  let datasetStatus: 'running' | 'success' | 'failed' | '' = ''
  let datasetError = ''
  let datasetDuration = 0

  let followUpStatus: 'running' | 'success' | 'failed' | '' = ''
  let followUpError = ''
  let followUpDuration = 0

  async function runTests() {
    if (!identifier) return

    identifierStatus = 'running'
    identifierError = ''
    identifierDuration = 0
    consignments = []

    datasetStatus = ''
    datasetError = ''
    datasetDuration = 0

    followUpStatus = ''
    followUpError = ''
    followUpDuration = 0

    let platformId = ''
    let datasetId = ''
    const datasetRequestId = crypto.randomUUID()
    const identifierStart = Date.now()

    try {
      const res = await api.post(`tests/${encodeURIComponent(gate.id)}?identifier=${encodeURIComponent(identifier)}`)

      if (!Array.isArray(res)) throw new Error('Invalid response structure')

      consignments = res

      if (consignments.length === 0) {
        throw new Error('No consignments found for this specific gate')
      }

      identifierStatus = 'success'
    } catch (err: any) {
      identifierStatus = 'failed'
      identifierError = err.message || 'Lookup failure'
      return
    } finally {
      identifierDuration = Date.now() - identifierStart
    }

    datasetStatus = 'running'
    const datasetStart = Date.now()
    try {
      const firstMatch = consignments[0]
      platformId = firstMatch.uil?.platformId || firstMatch.platformId
      datasetId = firstMatch.uil?.datasetId || firstMatch.datasetId

      if (!platformId || !datasetId) {
        throw new Error('Missing platformId or datasetId references inside the payload')
      }

      await eftiApi.requestXml(`dataset/${gate.id}/${platformId}/${datasetId}?subsetId=EU01`, {
        headers: {'X-Request-ID': datasetRequestId }
      })
      datasetStatus = 'success'
    } catch (err: any) {
      datasetStatus = 'failed'
      datasetError = err.message || 'Dataset failure'
      return
    } finally {
      datasetDuration = Date.now() - datasetStart
    }

    followUpStatus = 'running'
    const followUpStart = Date.now()
    try {
      await eftiApi.post(`follow-up/${gate.id}/${platformId}/${datasetId}/${datasetRequestId}`, 'Automatic test follow-up', {
        'X-Request-ID': crypto.randomUUID()
      })
      followUpStatus = 'success'
    } catch (err: any) {
      followUpStatus = 'failed'
      followUpError = err.message || 'Follow-up failure'
    } finally {
      followUpDuration = Date.now() - followUpStart
    }
  }
</script>

<div class="space-y-4">
  <div class="flex items-end gap-2">
    <div class="flex-1">
      <FormField label={t.identifiers.identifier} bind:value={identifier} required={true} placeholder={t.identifiers.identifierPlaceholder}/>
    </div>
    <Button label={t.general.runTests} onclick={runTests} class="primary"
      disabled={!identifier || identifierStatus === 'running' || datasetStatus === 'running' || followUpStatus === 'running'}
    />
  </div>

  {#if identifierStatus}
    <div class="text-sm">
      {t.datasets.identifierTestResult}
      {#if identifierStatus === 'running'}
        <span class="text-primary-600">{t.general.running}</span>
      {:else if identifierStatus === 'success'}
        <span class="text-success-700">{t.general.success} ({identifierDuration}ms)</span>
      {:else if identifierStatus === 'failed'}
        <span class="text-danger-700">{t.general.failed} ({identifierError}) ({identifierDuration}ms)</span>
      {/if}
    </div>
  {/if}

  {#if datasetStatus}
    <div class="text-sm">
      {t.datasets.datasetTestResult}
      {#if datasetStatus === 'running'}
        <span class="text-primary-600">{t.general.running}</span>
      {:else if datasetStatus === 'success'}
        <span class="text-success-700">{t.general.success} ({datasetDuration}ms)</span>
      {:else if datasetStatus === 'failed'}
        <span class="text-danger-700">{t.general.failed} ({datasetError}) ({datasetDuration}ms)</span>
      {/if}
    </div>
  {/if}

  {#if followUpStatus}
    <div class="text-sm">
      {t.datasets.followUpTestResult}
      {#if followUpStatus === 'running'}
        <span class="text-primary-600">{t.general.running}</span>
      {:else if followUpStatus === 'success'}
        <span class="text-success-700">{t.general.success} ({followUpDuration}ms)</span>
      {:else if followUpStatus === 'failed'}
        <span class="text-danger-700">{t.general.failed} ({followUpError}) ({followUpDuration}ms)</span>
      {/if}
    </div>
  {/if}
</div>
