<script lang="ts">
  import type {Gate, Platform} from 'src/api/types'
  import api, {eftiApi} from 'src/api/api'
  import FormField from 'src/forms/FormField.svelte'
  import {t} from 'i18n'
  import Button from 'src/components/Button.svelte'

  export let platform: Platform

  let ownGateId: string | undefined
  api.get<Gate>('gates/own').then(g => ownGateId = g.id)

  let datasetId = ''

  let datasetStatus: 'running' | 'success' | 'failed' | '' = ''
  let datasetError = ''
  let datasetDuration = 0

  let followUpStatus: 'running' | 'success' | 'failed' | '' = ''
  let followUpError = ''
  let followUpDuration = 0

  async function runTests() {
    if (!datasetId || !ownGateId) return

    datasetStatus = 'running'
    datasetError = ''
    datasetDuration = 0

    followUpStatus = ''
    followUpError = ''
    followUpDuration = 0

    const datasetRequestId = crypto.randomUUID()
    const datasetStart = Date.now()

    try {
      await eftiApi.requestXml(`dataset/${ownGateId}/${platform.id}/${datasetId}?subsetId=EU01`, {
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
      await eftiApi.post(`follow-up/${ownGateId}/${platform.id}/${datasetId}/${datasetRequestId}`, 'Automatic test follow-up', {
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
      <FormField label={t.datasets.datasetId} minlength={36} maxlength={36} bind:value={datasetId} required={true}/>
    </div>
    <Button label={t.general.runTests} onclick={runTests} class="primary"
            disabled={!datasetId || !ownGateId || datasetStatus === 'running' || followUpStatus === 'running'}
    />
  </div>

  {#if datasetStatus}
    <div class="text-sm">
      {t.datasets.datasetTestResult}
      {#if datasetStatus === 'running'}
        <span class="text-blue-600">{t.general.running}</span>
      {:else if datasetStatus === 'success'}
        <span class="text-green-600">{t.general.success} ({datasetDuration}ms)</span>
      {:else if datasetStatus === 'failed'}
        <span class="text-red-600">{t.general.failed} ({datasetError}) ({datasetDuration}ms)</span>
      {/if}
    </div>
  {/if}

  {#if followUpStatus}
    <div class="text-sm">
      {t.datasets.followUpTestResult}
      {#if followUpStatus === 'running'}
        <span class="text-blue-600">{t.general.running}</span>
      {:else if followUpStatus === 'success'}
        <span class="text-green-600">{t.general.success} ({followUpDuration}ms)</span>
      {:else if followUpStatus === 'failed'}
        <span class="text-red-600">{t.general.failed} ({followUpError}) ({followUpDuration}ms)</span>
      {/if}
    </div>
  {/if}
</div>
