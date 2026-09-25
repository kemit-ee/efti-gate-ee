import {fireEvent, render, waitFor} from '@testing-library/svelte'
import PlatformsPage from './PlatformsPage.svelte'
import api from 'src/api/api'
import {type Platform, Status} from 'src/api/ruuterTypes'

vi.mock('src/api/api', () => ({default: {get: vi.fn()}}))

const platforms: Platform[] = [{id: 'mock', baseUrl: 'https://mock.example/api', status: Status.ONLINE, createdAt: '2026-01-01T00:00:00Z'}]

describe('PlatformsPage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(api.get).mockResolvedValue(platforms)
  })

  it('loads and lists platforms, showing the count in the title', async () => {
    const {container} = render(PlatformsPage)
    await waitFor(() => expect(api.get).toHaveBeenCalledWith('platforms'))
    await waitFor(() => expect(container.textContent).to.contain('(1)'))
    expect(container.textContent).to.contain('mock')
  })

  it('opens the create-platform modal when Add is clicked', async () => {
    const {getByText} = render(PlatformsPage)
    await waitFor(() => expect(api.get).toHaveBeenCalled())

    await fireEvent.click(getByText('Add'))

    await waitFor(() => expect(document.body.classList.contains('modal-open')).to.be.true)
  })
})
