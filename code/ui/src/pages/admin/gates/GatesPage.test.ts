import {fireEvent, render, waitFor} from '@testing-library/svelte'
import GatesPage from './GatesPage.svelte'
import api from 'src/api/api'
import {type Gate, Status} from 'src/api/ruuterTypes'

vi.mock('src/api/api', () => ({default: {get: vi.fn()}}))

const gates: Gate[] = [{id: 'GW1', rowId: '1', countryCode: 'EE' as any, eDeliveryUrl: 'https://gw1.example/services/msh', status: Status.ONLINE, createdAt: '2026-01-01T00:00:00Z'}]

describe('GatesPage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(api.get).mockResolvedValue(gates)
  })

  it('loads gates and this gate own info, showing the count in the title', async () => {
    const {container} = render(GatesPage)
    await waitFor(() => expect(api.get).toHaveBeenCalledWith('gates'))
    expect(api.get).toHaveBeenCalledWith('gates/own')
    await waitFor(() => expect(container.textContent).to.contain('(1)'))
    expect(container.textContent).to.contain('GW1')
  })

  it('opens the create-gate modal when Add is clicked', async () => {
    const {getByText} = render(GatesPage)
    await waitFor(() => expect(api.get).toHaveBeenCalled())

    await fireEvent.click(getByText('Add'))

    await waitFor(() => expect(document.body.classList.contains('modal-open')).to.be.true)
  })
})
