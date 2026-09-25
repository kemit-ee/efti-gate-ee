import {fireEvent, render} from '@testing-library/svelte'
import UserForm from './UserForm.svelte'
import api from 'src/api/api'
import type {User} from 'src/api/ruuterTypes'

vi.mock('src/api/api', () => ({default: {post: vi.fn().mockResolvedValue(undefined), put: vi.fn().mockResolvedValue(undefined)}}))

describe('UserForm', () => {
  beforeEach(() => vi.clearAllMocks())

  it('creates a new user with the TARA sub field shown', async () => {
    const user = {} as User
    const onSaved = vi.fn()
    const {getByLabelText, getByText} = render(UserForm, {user, onSaved})

    expect(getByLabelText('TARA Sub')).to.exist

    await fireEvent.input(getByLabelText('TARA Sub'), {target: {value: 'EE60001019906'}})
    await fireEvent.input(getByLabelText('Name'), {target: {value: 'New User'}})
    await fireEvent.click(getByText('Save'))

    expect(api.post).toHaveBeenCalledWith('users', {taraSub: 'EE60001019906', name: 'New User'})
    expect(onSaved).toHaveBeenCalled()
  })

  it('updates an existing user without showing the TARA sub field', async () => {
    const user: User = {id: '60001019906', taraSub: 'EE60001019906', name: 'Super Admin', createdAt: '2026-01-01T00:00:00Z'}
    const onSaved = vi.fn()
    const {queryByLabelText, getByLabelText, getByText} = render(UserForm, {user, onSaved})

    expect(queryByLabelText('TARA Sub')).to.be.null

    await fireEvent.input(getByLabelText('Name'), {target: {value: 'Renamed'}})
    await fireEvent.click(getByText('Save'))

    expect(api.put).toHaveBeenCalledWith('users/60001019906', {taraSub: 'EE60001019906', name: 'Renamed'})
    expect(onSaved).toHaveBeenCalled()
  })
})
