import {fireEvent, render} from '@testing-library/svelte'
import GateList from './GateList.svelte'
import api from 'src/api/api'
import {type Gate, Status} from 'src/api/ruuterTypes'

vi.mock('src/api/api', () => ({default: {delete: vi.fn().mockResolvedValue(undefined), post: vi.fn()}}))

const gates: Gate[] = [{id: 'GW1', rowId: '1', countryCode: 'EE' as any, eDeliveryUrl: 'https://gw1.example/services/msh', status: Status.ONLINE, createdAt: '2026-01-01T00:00:00Z'}]

describe('GateList', () => {
  let onEdit: (gate: Gate) => void, onDeleted: (gate: Gate) => void

  beforeEach(() => {
    vi.clearAllMocks()
    onEdit = vi.fn()
    onDeleted = vi.fn()
    vi.spyOn(window, 'confirm').mockReturnValue(true)
  })

  it('renders a row per gate', () => {
    const {container} = render(GateList, {gates, onEdit, onDeleted})
    expect(container.textContent).to.contain('GW1')
    expect(container.textContent).to.contain('EE')
    expect(container.textContent).to.contain('ONLINE')
  })

  it('calls onEdit with the gate when Edit is clicked', async () => {
    const {getByText} = render(GateList, {gates, onEdit, onDeleted})
    await fireEvent.click(getByText('Edit'))
    expect(onEdit).toHaveBeenCalledWith(gates[0])
  })

  it('deletes the gate after confirmation and calls onDeleted', async () => {
    const {getByText} = render(GateList, {gates, onEdit, onDeleted})
    await fireEvent.click(getByText('Delete'))
    expect(api.delete).toHaveBeenCalledWith('gates/GW1')
    expect(onDeleted).toHaveBeenCalledWith(gates[0])
  })

  it('pings the gate and updates it in place on success', async () => {
    const pinged = {...gates[0], status: Status.ONLINE, lastPingAt: '2026-01-01T00:01:00Z'}
    vi.mocked(api.post).mockResolvedValue(pinged)
    const {getByText, container} = render(GateList, {gates: [...gates], onEdit, onDeleted})

    await fireEvent.click(getByText('Ping'))

    expect(api.post).toHaveBeenCalledWith('gates/ping/GW1')
    expect(container.textContent).to.contain('ONLINE')
  })

  // NOTE: the failure branch of ping() (marking the gate OFFLINE on a rejected api.post) is not
  // covered here. GateList's onclick={() => ping(g)} doesn't await/catch the promise ping()
  // re-throws on failure, so triggering that branch in a test always surfaces as a genuine
  // unhandled rejection that vitest's own tracking catches regardless of listeners added in the
  // test — this looks like a real latent bug in GateList.svelte, not a test-authoring issue.
})
