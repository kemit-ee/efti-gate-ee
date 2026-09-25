import {fireEvent, render, waitFor} from '@testing-library/svelte'
import OwnGateButton from './OwnGateButton.svelte'
import api from 'src/api/api'
import {type Gate, Status} from 'src/api/ruuterTypes'

vi.mock('src/api/api', () => ({default: {get: vi.fn()}}))

const ownGate: Gate = {id: 'EE', rowId: '1', countryCode: 'EE' as any, eDeliveryUrl: 'https://own.example/services/msh', status: Status.ONLINE, createdAt: '2026-01-01T00:00:00Z'}

describe('OwnGateButton', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(api.get).mockResolvedValue(ownGate)
  })

  it('does not load the own gate until clicked', () => {
    render(OwnGateButton)
    expect(api.get).not.toHaveBeenCalled()
  })

  it('loads and shows the own gate as a read-only form when clicked', async () => {
    const {getByText, getByLabelText} = render(OwnGateButton)

    await fireEvent.click(getByText('This gate details'))

    await waitFor(() => expect(api.get).toHaveBeenCalledWith('gates/own'))
    await waitFor(() => expect((getByLabelText('Gate ID') as HTMLInputElement).value).to.equal('EE'))
    expect((getByLabelText('Gate ID') as HTMLInputElement).disabled).to.be.true
  })
})
