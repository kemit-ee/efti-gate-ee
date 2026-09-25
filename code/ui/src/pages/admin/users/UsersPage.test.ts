import {fireEvent, render, waitFor} from '@testing-library/svelte'
import UsersPage from './UsersPage.svelte'
import api from 'src/api/api'
import type {User} from 'src/api/ruuterTypes'

vi.mock('src/api/api', () => ({default: {get: vi.fn()}}))

const users: User[] = [{id: '60001019906', taraSub: 'EE60001019906', name: 'Super Admin', createdAt: '2026-01-01T00:00:00Z'}]

describe('UsersPage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(api.get).mockResolvedValue(users)
  })

  it('loads and lists users, showing the count in the title', async () => {
    const {container} = render(UsersPage)
    await waitFor(() => expect(api.get).toHaveBeenCalledWith('users'))
    await waitFor(() => expect(container.textContent).to.contain('(1)'))
    expect(container.textContent).to.contain('Super Admin')
  })

  it('opens the create-user modal when Add is clicked', async () => {
    const {getByText} = render(UsersPage)
    await waitFor(() => expect(api.get).toHaveBeenCalled())

    await fireEvent.click(getByText('Add'))

    await waitFor(() => expect(document.body.classList.contains('modal-open')).to.be.true)
  })
})
