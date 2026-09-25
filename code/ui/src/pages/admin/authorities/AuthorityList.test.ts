import {fireEvent, render} from '@testing-library/svelte'
import AuthorityList from './AuthorityList.svelte'
import api from 'src/api/api'
import {type Authority, Status, SubsetCode} from 'src/api/ruuterTypes'

vi.mock('src/api/api', () => ({default: {delete: vi.fn().mockResolvedValue(undefined)}}))

const authorities: Authority[] = [{id: 'PPA', name: 'Police and Border Guard', registryCode: '70008747', subsets: [SubsetCode.EU01, SubsetCode.EU02], status: Status.ONLINE, createdAt: '2026-01-01T00:00:00Z'}]

describe('AuthorityList', () => {
  let onEdit: (authority: Authority) => void, onDeleted: (authority: Authority) => void

  beforeEach(() => {
    vi.clearAllMocks()
    onEdit = vi.fn()
    onDeleted = vi.fn()
    vi.spyOn(window, 'confirm').mockReturnValue(true)
  })

  it('renders a row per authority', () => {
    const {container} = render(AuthorityList, {authorities, onEdit, onDeleted})
    expect(container.textContent).to.contain('PPA')
    expect(container.textContent).to.contain('Police and Border Guard')
    expect(container.textContent).to.contain('70008747')
    expect(container.textContent).to.contain('EU01, EU02')
  })

  it('calls onEdit with the authority when Edit is clicked', async () => {
    const {getByText} = render(AuthorityList, {authorities, onEdit, onDeleted})
    await fireEvent.click(getByText('Edit'))
    expect(onEdit).toHaveBeenCalledWith(authorities[0])
  })

  it('deletes the authority after confirmation and calls onDeleted', async () => {
    const {getByText} = render(AuthorityList, {authorities, onEdit, onDeleted})
    await fireEvent.click(getByText('Delete'))
    expect(window.confirm).toHaveBeenCalled()
    expect(api.delete).toHaveBeenCalledWith('authorities/PPA')
    expect(onDeleted).toHaveBeenCalledWith(authorities[0])
  })

  it('does not delete when confirmation is declined', async () => {
    vi.spyOn(window, 'confirm').mockReturnValue(false)
    const {getByText} = render(AuthorityList, {authorities, onEdit, onDeleted})
    await fireEvent.click(getByText('Delete'))
    expect(api.delete).not.toHaveBeenCalled()
    expect(onDeleted).not.toHaveBeenCalled()
  })
})
