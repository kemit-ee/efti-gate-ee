<script lang="ts">
  import {t} from 'i18n'
  import GateList from 'src/pages/admin/gates/GateList.svelte'
  import GateForm from 'src/pages/admin/gates/GateForm.svelte'
  import {onMount} from 'svelte'
  import api from 'src/api/api'
  import Modal from 'src/components/Modal.svelte'
  import type {Gate} from "src/api/ruuterTypes";
  import OwnGateButton from "src/pages/admin/gates/OwnGateButton.svelte";

  let gates: Gate[]
  let detailsGate: Gate | false = false

  onMount(load)

  async function load() {
    gates = await api.get<Gate[]>('gates')
    await api.get('gates/own')
  }
</script>

<div class="mb-6 flex justify-between items-center gap-8">
  <h1>
    {t.gates.title} ({gates?.length})
  </h1>
  <div>
    <OwnGateButton/>
  </div>
</div>

<GateList {gates} onDetails={gate => detailsGate = gate}/>

<Modal bind:show={detailsGate} title={t.gates.gate}>
  {#if detailsGate}
    <GateForm gate={detailsGate} disabled/>
  {/if}
</Modal>
