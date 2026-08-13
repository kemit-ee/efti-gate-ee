<script lang="ts">
  import {t} from 'i18n'
  import GateList from 'src/pages/admin/gates/GateList.svelte'
  import GateForm from 'src/pages/admin/gates/GateForm.svelte'
  import {onMount} from 'svelte'
  import api from 'src/api/api'
  import Button from 'src/components/Button.svelte'
  import Modal from 'src/components/Modal.svelte'
  import OwnGateButton from 'src/pages/admin/gates/OwnGateButton.svelte'
  import {showToast, ToastType} from 'src/stores/toasts'
  import {navigate} from 'src/router'
  import {user} from 'src/stores/auth'
  import GateTestForm from 'src/pages/admin/gates/GateTestForm.svelte'
  import type {Gate, RuuterResponse} from "src/api/ruuterTypes";

  let gates: Gate[]
  let editGate: Gate | false = false
  let testGate: Gate | false = false

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

  function onTest(gate: Gate) {
    testGate = gate
  }

  function onSaved(gate: Gate, isNew: boolean) {
    editGate = false
    if (isNew) {
      showToast(t.users.createForAdmin, {type: ToastType.INFO, timeoutSec: 10})
      navigate(`/users?gateId=${gate.id}`)
    } else load()
  }
</script>

<h1 class="mb-6 flex justify-between items-center gap-8">
  {t.gates.title} ({gates?.length})
  <span>
    <OwnGateButton/>
    <Button label={t.general.add} onclick={add} class="primary"/>
  </span>
</h1>

<GateList {gates} onEdit={onEdit} onDeleted={load} onTest={onTest}/>

<Modal bind:show={editGate} title={t.gates.gate}>
  {#if editGate}
    <GateForm gate={editGate} {onSaved}/>
  {/if}
</Modal>

<Modal bind:show={testGate} title="{t.general.test}: {testGate ? testGate.id : ''}">
  {#if testGate}
    <GateTestForm gate={testGate} />
  {/if}
</Modal>
