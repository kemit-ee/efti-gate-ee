import {fireEvent, render} from '@testing-library/svelte'
import UserList from './UserList.svelte'
import api from 'src/api/api'
import type {User} from 'src/api/ruuterTypes'

vi.mock('src/api/api', () => ({default: {delete: vi.fn().mockResolvedValue(undefined), post: vi.fn().mockResolvedValue(undefined)}}))

const users: User[] = [{id: '60001019906', taraSub: 'EE60001019906', name: 'Super Admin', createdAt: '2026-01-01T00:00:00Z'}]

describe('UserList', () => {
  let onEdit: (user: User) => void, load: () => void

  beforeEach(() => {
    vi.clearAllMocks()
    onEdit = vi.fn()
    load = vi.fn()
    vi.spyOn(window, 'confirm').mockReturnValue(true)
  })

  it('renders a row per user', () => {
    const {container} = render(UserList, {users, onEdit, load})
    expect(container.textContent).to.contain('60001019906')
    expect(container.textContent).to.contain('Super Admin')
    expect(container.textContent).to.contain('EE60001019906')
  })

  it('calls onEdit with the user when Edit is clicked', async () => {
    const {getByText} = render(UserList, {users, onEdit, load})
    await fireEvent.click(getByText('Edit'))
    expect(onEdit).toHaveBeenCalledWith(users[0])
  })

  it('deletes the user after confirmation and reloads', async () => {
    const {getByText} = render(UserList, {users, onEdit, load})
    await fireEvent.click(getByText('Delete'))
    expect(window.confirm).toHaveBeenCalled()
    expect(api.delete).toHaveBeenCalledWith('users/60001019906')
    expect(load).toHaveBeenCalled()
  })

  it('does not delete when confirmation is declined', async () => {
    vi.spyOn(window, 'confirm').mockReturnValue(false)
    const {getByText} = render(UserList, {users, onEdit, load})
    await fireEvent.click(getByText('Delete'))
    expect(api.delete).not.toHaveBeenCalled()
    expect(load).not.toHaveBeenCalled()
  })

  it('revokes the token after confirmation and reloads', async () => {
    const {getByText} = render(UserList, {users, onEdit, load})
    await fireEvent.click(getByText('Revoke token'))
    expect(api.post).toHaveBeenCalledWith('users/revoke-token/60001019906')
    expect(load).toHaveBeenCalled()
  })

  it('does not revoke the token when confirmation is declined', async () => {
    vi.spyOn(window, 'confirm').mockReturnValue(false)
    const {getByText} = render(UserList, {users, onEdit, load})
    await fireEvent.click(getByText('Revoke token'))
    expect(api.post).not.toHaveBeenCalled()
    expect(load).not.toHaveBeenCalled()
  })
})
