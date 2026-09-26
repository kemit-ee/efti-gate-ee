import {render, waitFor, fireEvent, screen} from '@testing-library/svelte'
import UsersPage from './UsersPage.svelte'
import api from 'src/api/api'
import type {User} from 'src/api/ruuterTypes'

vi.mock('src/api/api', () => ({default: {get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn()}}))

function newUser(): User {
  return {id: '60001019906', taraSub: 'EE60001019906', name: 'Super Admin', createdAt: '2026-01-01T00:00:00Z'}
}

describe('UsersPage', () => {
  let user: User

  beforeEach(() => {
    vi.clearAllMocks()
    user = newUser()
    vi.mocked(api.get).mockImplementation((path: string) => {
      if (path === 'users') return Promise.resolve([user])
      return Promise.reject(new Error(`unexpected GET ${path}`))
    })
  })

  it('loads users on mount', async () => {
    render(UsersPage)

    await waitFor(() => expect(api.get).toHaveBeenCalledWith('users'))
    await screen.findByText('Super Admin')
    screen.getByText('Users (1)')
  })

  it('adds a new user', async () => {
    vi.mocked(api.post).mockResolvedValue({})
    render(UsersPage)
    await screen.findByText('Super Admin')

    await fireEvent.click(screen.getByRole('button', {name: 'Add'}))
    await fireEvent.input(screen.getByLabelText('TARA Sub'), {target: {value: 'EE60002020202'}})
    await fireEvent.input(screen.getByLabelText('Name'), {target: {value: 'New User'}})
    await fireEvent.click(screen.getByRole('button', {name: 'Save'}))

    await waitFor(() => expect(api.post).toHaveBeenCalledWith('users', {taraSub: 'EE60002020202', name: 'New User'}))
  })

  it('edits an existing user without exposing a TARA Sub field', async () => {
    vi.mocked(api.put).mockResolvedValue({})
    render(UsersPage)
    await screen.findByText('Super Admin')

    await fireEvent.click(screen.getByRole('button', {name: 'Edit'}))
    expect(screen.queryByLabelText('TARA Sub')).to.equal(null)
    await fireEvent.input(screen.getByLabelText('Name'), {target: {value: 'Renamed Admin'}})
    await fireEvent.click(screen.getByRole('button', {name: 'Save'}))

    await waitFor(() => expect(api.put).toHaveBeenCalledWith('users/60001019906', {taraSub: 'EE60001019906', name: 'Renamed Admin'}))
  })

  it('deletes a user after confirmation', async () => {
    vi.spyOn(window, 'confirm').mockReturnValue(true)
    vi.mocked(api.delete).mockResolvedValue({})
    render(UsersPage)
    await screen.findByText('Super Admin')

    await fireEvent.click(screen.getByRole('button', {name: 'Delete'}))

    await waitFor(() => expect(api.delete).toHaveBeenCalledWith('users/60001019906'))
  })

  it('revokes a token after confirmation', async () => {
    vi.spyOn(window, 'confirm').mockReturnValue(true)
    vi.mocked(api.post).mockResolvedValue({})
    render(UsersPage)
    await screen.findByText('Super Admin')

    await fireEvent.click(screen.getByRole('button', {name: 'Revoke token'}))

    await waitFor(() => expect(api.post).toHaveBeenCalledWith('users/revoke-token/60001019906'))
  })

  it('does not revoke a token when the confirmation is declined', async () => {
    vi.spyOn(window, 'confirm').mockReturnValue(false)
    render(UsersPage)
    await screen.findByText('Super Admin')

    await fireEvent.click(screen.getByRole('button', {name: 'Revoke token'}))

    expect(api.post).not.toHaveBeenCalled()
  })
})
