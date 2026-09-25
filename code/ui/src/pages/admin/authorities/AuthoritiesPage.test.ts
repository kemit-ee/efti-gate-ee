import {fireEvent, render, waitFor} from '@testing-library/svelte'
import AuthoritiesPage from './AuthoritiesPage.svelte'
import api from 'src/api/api'
import {type Authority, Status, SubsetCode} from 'src/api/ruuterTypes'

vi.mock('src/api/api', () => ({default: {get: vi.fn()}}))

const authorities: Authority[] = [{id: 'PPA', name: 'Police and Border Guard', registryCode: '70008747', subsets: [SubsetCode.EU01], status: Status.ONLINE, createdAt: '2026-01-01T00:00:00Z'}]

describe('AuthoritiesPage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(api.get).mockResolvedValue(authorities)
  })

  it('loads and lists authorities, showing the count in the title', async () => {
    const {container} = render(AuthoritiesPage)
    await waitFor(() => expect(api.get).toHaveBeenCalledWith('authorities'))
    await waitFor(() => expect(container.textContent).to.contain('(1)'))
    expect(container.textContent).to.contain('Police and Border Guard')
  })

  it('opens the create-authority modal, pre-seeded with an empty subsets array, when Add is clicked', async () => {
    const {getByText} = render(AuthoritiesPage)
    await waitFor(() => expect(api.get).toHaveBeenCalled())

    await fireEvent.click(getByText('Add'))

    await waitFor(() => expect(document.body.classList.contains('modal-open')).to.be.true)
  })
})
