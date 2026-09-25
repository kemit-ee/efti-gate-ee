import {fireEvent, render, waitFor} from '@testing-library/svelte'
import ConsignmentPage from './ConsignmentPage.svelte'
import api from 'src/api/api'
import type {Consignment} from 'src/api/ruuterTypes'

vi.mock('src/api/api', () => ({default: {get: vi.fn()}}))

const consignment: Consignment = {
  datasetId: 'ds-1', platformId: 'mock', gateId: 'EE', status: 'ACTIVE' as any, xml: '<Consignment/>',
  dangerousGoods: 'false', createdAt: '2026-01-01T00:00:00Z', usedEquipmentIds: [], carriedEquipmentIds: [], mainTransportId: 'TRK-1'
}

describe('ConsignmentPage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(api.get).mockResolvedValue([consignment])
  })

  it('loads consignments on mount', async () => {
    const {container} = render(ConsignmentPage)
    await waitFor(() => expect(api.get).toHaveBeenCalledWith('consignments'))
    expect(container.textContent).to.contain('ds-1')
  })

  it('reloads from scratch (debounced) when the filter changes', async () => {
    vi.useFakeTimers({toFake: ['setTimeout', 'clearTimeout']})
    const {getByPlaceholderText} = render(ConsignmentPage)
    await vi.waitFor(() => expect(api.get).toHaveBeenCalledTimes(1))

    await fireEvent.input(getByPlaceholderText('Filter...'), {target: {value: 'ds-'}})
    expect(api.get).toHaveBeenCalledTimes(1)

    await vi.advanceTimersByTimeAsync(500)
    expect(api.get).toHaveBeenCalledTimes(2)
    vi.useRealTimers()
  })

  it('reloads immediately when the cabotage-only checkbox is toggled', async () => {
    const {getByLabelText} = render(ConsignmentPage)
    await waitFor(() => expect(api.get).toHaveBeenCalledTimes(1))

    await fireEvent.click(getByLabelText('Show only cabotage'))

    await waitFor(() => expect(api.get).toHaveBeenCalledTimes(2))
  })
})
