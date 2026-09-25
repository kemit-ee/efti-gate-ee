import {fireEvent, render} from '@testing-library/svelte'
import AuthorityForm from './AuthorityForm.svelte'
import api from 'src/api/api'
import {type Authority, type Subset, Status, SubsetCode} from 'src/api/ruuterTypes'

vi.mock('src/api/api', () => ({default: {post: vi.fn().mockResolvedValue(undefined), put: vi.fn().mockResolvedValue(undefined)}}))

describe('AuthorityForm', () => {
  beforeEach(() => vi.clearAllMocks())

  it('creates a new authority with an editable id and the entered subset', async () => {
    const authority = {subsets: [] as Subset[]} as Authority
    const onSaved = vi.fn()
    const {getByLabelText, container, getByText} = render(AuthorityForm, {authority, onSaved})

    expect((getByLabelText('Authority ID') as HTMLInputElement).disabled).to.be.false

    await fireEvent.input(getByLabelText('Authority ID'), {target: {value: 'PPA'}})
    await fireEvent.input(getByLabelText('Authority name'), {target: {value: 'Police and Border Guard'}})
    await fireEvent.input(getByLabelText('Registry code'), {target: {value: '70008747'}})
    await fireEvent.input(container.querySelector('.subset input')!, {target: {value: 'EU01'}})
    await fireEvent.click(getByText('Save'))

    expect(api.post).toHaveBeenCalledWith('authorities', {id: 'PPA', name: 'Police and Border Guard', registryCode: '70008747', subsets: ['EU01']})
    expect(onSaved).toHaveBeenCalled()
  })

  it('updates an existing authority with a disabled id field', async () => {
    const authority: Authority = {id: 'PPA', name: 'Police and Border Guard', registryCode: '70008747', subsets: [SubsetCode.EU01], status: Status.ONLINE, createdAt: '2026-01-01T00:00:00Z'}
    const onSaved = vi.fn()
    const {getByLabelText, getByText} = render(AuthorityForm, {authority, onSaved})

    expect((getByLabelText('Authority ID') as HTMLInputElement).disabled).to.be.true

    await fireEvent.input(getByLabelText('Authority name'), {target: {value: 'Renamed Authority'}})
    await fireEvent.click(getByText('Save'))

    expect(api.put).toHaveBeenCalledWith('authorities/PPA', {id: 'PPA', name: 'Renamed Authority', registryCode: '70008747', subsets: ['EU01']})
    expect(onSaved).toHaveBeenCalled()
  })
})
