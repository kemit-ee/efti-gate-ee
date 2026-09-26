import {render, waitFor, fireEvent, screen} from '@testing-library/svelte'
import AuthoritiesPage from './AuthoritiesPage.svelte'
import api from 'src/api/api'
import {SubsetCode, Status} from 'src/api/ruuterTypes'
import type {Authority} from 'src/api/ruuterTypes'

vi.mock('src/api/api', () => ({default: {get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn()}}))

function newAuthority(): Authority {
  return {id: 'PRIA', name: 'Agriculture Registry', registryCode: '70006440', subsets: [SubsetCode.EU01], status: Status.ONLINE, createdAt: '2026-01-01T00:00:00Z'}
}

describe('AuthoritiesPage', () => {
  let authority: Authority

  beforeEach(() => {
    vi.clearAllMocks()
    authority = newAuthority()
    vi.mocked(api.get).mockImplementation((path: string) => {
      if (path === 'authorities') return Promise.resolve([authority])
      return Promise.reject(new Error(`unexpected GET ${path}`))
    })
  })

  it('loads authorities on mount', async () => {
    render(AuthoritiesPage)

    await waitFor(() => expect(api.get).toHaveBeenCalledWith('authorities'))
    await screen.findByText('PRIA')
    screen.getByText('Authorities (1)')
    screen.getByText('EU01')
  })

  it('adds a new authority with its first subset', async () => {
    vi.mocked(api.post).mockResolvedValue({})
    render(AuthoritiesPage)
    await screen.findByText('PRIA')

    await fireEvent.click(screen.getByRole('button', {name: 'Add'}))
    await fireEvent.input(screen.getByLabelText('Authority ID'), {target: {value: 'MOCK'}})
    await fireEvent.input(screen.getByLabelText('Authority name'), {target: {value: 'Mock Authority'}})
    await fireEvent.input(screen.getByLabelText('Registry code'), {target: {value: '12345678'}})
    const subsetInput = await waitFor(() => {
      const el = document.querySelector('.subset input') as HTMLInputElement
      if (!el) throw new Error('subset input not rendered yet')
      return el
    })
    await fireEvent.input(subsetInput, {target: {value: 'EU01'}})

    await fireEvent.click(screen.getByRole('button', {name: 'Save'}))

    await waitFor(() => expect(api.post).toHaveBeenCalledWith('authorities', {
      id: 'MOCK', name: 'Mock Authority', registryCode: '12345678', subsets: ['EU01'],
    }))
  })

  it('adds and removes an extra subset row', async () => {
    vi.mocked(api.post).mockResolvedValue({})
    render(AuthoritiesPage)
    await screen.findByText('PRIA')

    await fireEvent.click(screen.getByRole('button', {name: 'Add'}))
    await waitFor(() => expect(document.querySelectorAll('.subset input').length).to.equal(1))
    await fireEvent.click(screen.getByRole('button', {name: '+'}))
    let subsetInputs: NodeListOf<Element> = []as any
    await waitFor(() => {
      subsetInputs = document.querySelectorAll('.subset input')
      expect(subsetInputs.length).to.equal(2)
    })

    const removeButtons = screen.getAllByRole('button', {name: '×'})
    await fireEvent.click(removeButtons[0])

    subsetInputs = document.querySelectorAll('.subset input')
    expect(subsetInputs.length).to.equal(1)
  })

  it('edits an existing authority', async () => {
    vi.mocked(api.put).mockResolvedValue({})
    render(AuthoritiesPage)
    await screen.findByText('PRIA')

    await fireEvent.click(screen.getByRole('button', {name: 'Edit'}))
    expect((screen.getByLabelText('Authority ID') as HTMLInputElement).disabled).to.equal(true)
    await fireEvent.input(screen.getByLabelText('Authority name'), {target: {value: 'Renamed Authority'}})
    await fireEvent.click(screen.getByRole('button', {name: 'Save'}))

    await waitFor(() => expect(api.put).toHaveBeenCalledWith('authorities/PRIA', expect.objectContaining({
      name: 'Renamed Authority',
    })))
  })

  it('deletes an authority after confirmation', async () => {
    vi.spyOn(window, 'confirm').mockReturnValue(true)
    vi.mocked(api.delete).mockResolvedValue({})
    render(AuthoritiesPage)
    await screen.findByText('PRIA')

    await fireEvent.click(screen.getByRole('button', {name: 'Delete'}))

    await waitFor(() => expect(api.delete).toHaveBeenCalledWith('authorities/PRIA'))
  })

  it('does not delete when the confirmation is declined', async () => {
    vi.spyOn(window, 'confirm').mockReturnValue(false)
    render(AuthoritiesPage)
    await screen.findByText('PRIA')

    await fireEvent.click(screen.getByRole('button', {name: 'Delete'}))

    expect(api.delete).not.toHaveBeenCalled()
  })
})
