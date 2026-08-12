<script lang="ts">
  import {t} from 'i18n'
  import Button from 'src/components/Button.svelte'
  import api from 'src/api/api'
  import type {Gate} from 'src/api/types'
  import Modal from 'src/components/Modal.svelte'
  import GateForm from 'src/pages/admin/gates/GateForm.svelte'

  let show = false
  let gate: Gate | undefined

  $: if (show) api.get<Gate>('gates/own').then(g => gate = g)
</script>

<Button label={t.gates.ownGate} onclick={() => show = true}/>

<Modal bind:show title={t.gates.ownGate} description={t.gates.ownGateDescription}>
  {#if gate}
    <GateForm {gate} disabled/>
  {/if}
</Modal>
