import {fireEvent, render, waitFor} from '@testing-library/svelte'
import GateForm from './GateForm.svelte'
import api from 'src/api/api'
import {type Gate, Status} from 'src/api/ruuterTypes'

vi.mock('src/api/api', () => ({default: {post: vi.fn().mockResolvedValue(undefined), put: vi.fn().mockResolvedValue(undefined)}}))

const CERT = '-----BEGIN CERTIFICATE-----\nabc\n-----END CERTIFICATE-----'

describe('GateForm', () => {
  beforeEach(() => vi.clearAllMocks())

  it('creates a new gate as OFFLINE and pings it', async () => {
    const gate = {} as Gate
    const onSaved = vi.fn()
    const {getByLabelText, getByText} = render(GateForm, {gate, onSaved})

    await fireEvent.input(getByLabelText('Gate ID'), {target: {value: 'GW1'}})
    await fireEvent.change(getByLabelText('Country'), {target: {value: 'EE'}})
    await fireEvent.input(getByLabelText('eDelivery URL'), {target: {value: 'https://gw1.example/services/msh'}})
    await fireEvent.input(getByLabelText('eDelivery certificate'), {target: {value: CERT}})
    await fireEvent.click(getByText('Save'))

    await waitFor(() => expect(api.post).toHaveBeenCalledWith('gates', {
      id: 'GW1', countryCode: 'EE', eDeliveryUrl: 'https://gw1.example/services/msh',
      eDeliveryCert: CERT, tlsCert: '', status: Status.OFFLINE
    }))
    expect(api.post).toHaveBeenCalledWith('gates/ping/GW1')
    expect(onSaved).toHaveBeenCalled()
  })

  it('updates an existing gate with a disabled id field', async () => {
    const gate: Gate = {id: 'GW1', rowId: '1', countryCode: 'EE' as any, eDeliveryUrl: 'https://gw1.example/services/msh', eDeliveryCert: CERT, tlsCert: '', status: Status.ONLINE, createdAt: '2026-01-01T00:00:00Z'}
    const onSaved = vi.fn()
    const {getByLabelText, getByText} = render(GateForm, {gate, onSaved})

    expect((getByLabelText('Gate ID') as HTMLInputElement).disabled).to.be.true

    await fireEvent.click(getByText('Save'))

    await waitFor(() => expect(api.put).toHaveBeenCalledWith('gates/GW1', {
      id: 'GW1', countryCode: 'EE', eDeliveryUrl: 'https://gw1.example/services/msh',
      eDeliveryCert: CERT, tlsCert: '', status: Status.OFFLINE
    }))
    expect(api.post).toHaveBeenCalledWith('gates/ping/GW1')
    expect(onSaved).toHaveBeenCalled()
  })

  it('marks the gate DISABLED and skips the ping when the checkbox is checked', async () => {
    const gate: Gate = {id: 'GW1', rowId: '1', countryCode: 'EE' as any, eDeliveryUrl: 'https://gw1.example/services/msh', eDeliveryCert: CERT, tlsCert: '', status: Status.ONLINE, createdAt: '2026-01-01T00:00:00Z'}
    const onSaved = vi.fn()
    const {getByLabelText, getByText} = render(GateForm, {gate, onSaved})

    await fireEvent.click(getByLabelText('Disabled'))
    await fireEvent.click(getByText('Save'))

    await waitFor(() => expect(api.put).toHaveBeenCalledWith('gates/GW1', {
      id: 'GW1', countryCode: 'EE', eDeliveryUrl: 'https://gw1.example/services/msh',
      eDeliveryCert: CERT, tlsCert: '', status: Status.DISABLED
    }))
    expect(api.post).not.toHaveBeenCalled()
  })

  it('renders read-only when disabled, with no save button or file inputs', () => {
    const gate: Gate = {id: 'GW1', rowId: '1', countryCode: 'EE' as any, eDeliveryUrl: 'https://gw1.example/services/msh', eDeliveryCert: CERT, tlsCert: '', status: Status.ONLINE, createdAt: '2026-01-01T00:00:00Z'}
    const {queryByText, container} = render(GateForm, {gate, disabled: true})

    expect(queryByText('Save')).to.be.null
    expect(container.querySelector('input[type=file]')).to.be.null
  })
})
