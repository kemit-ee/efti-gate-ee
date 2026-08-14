<script lang="ts">
  import {t} from 'i18n'
  import GateList from 'src/pages/admin/gates/GateList.svelte'
  import GateForm from 'src/pages/admin/gates/GateForm.svelte'
  import {onMount} from 'svelte'
  import api from 'src/api/api'
  import Button from 'src/components/Button.svelte'
  import Modal from 'src/components/Modal.svelte'
  import OwnGateButton from 'src/pages/admin/gates/OwnGateButton.svelte'
  import type {Gate} from "src/api/ruuterTypes";

  let gates: Gate[]
  let editGate: Gate | false = false

  onMount(load)

  async function load() {
    gates = await api.get<Gate[]>('v1/gates')
  }

  function add() {
    editGate = {} as Gate
  }

  function onEdit(gate: Gate) {
    editGate = gate
  }

  function onSaved() {
    editGate = false
    load()
  }
</script>

<h1 class="mb-6 flex justify-between items-center gap-8">
  {t.gates.title} ({gates?.length})
  <span>
    <OwnGateButton/>
    <Button label={t.general.add} onclick={add} class="primary"/>
  </span>
</h1>

<GateList {gates} onEdit={onEdit} onDeleted={load}/>

<Modal bind:show={editGate} title={t.gates.gate}>
  {#if editGate}
    <GateForm gate={editGate} {onSaved}/>
  {/if}
</Modal>