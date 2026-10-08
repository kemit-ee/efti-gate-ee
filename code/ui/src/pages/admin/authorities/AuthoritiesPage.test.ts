import {fireEvent, render, screen, waitFor} from '@testing-library/svelte'
import AuthoritiesPage from './AuthoritiesPage.svelte'
import api from 'src/api/api'
import type {Authority} from 'src/api/ruuterTypes'
import {Status, SubsetCode} from 'src/api/ruuterTypes'

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

  it('does not offer adding an authority, the registry is declarative', async () => {
    render(AuthoritiesPage)
    await screen.findByText('PRIA')

    for (const name of ['Add', 'Edit', 'Delete']) {
      expect(screen.queryByRole('button', {name})).to.equal(null)
    }
  })

  it('shows an authority in read-only details without calling the API', async () => {
    render(AuthoritiesPage)
    await screen.findByText('PRIA')

    await fireEvent.click(screen.getByRole('button', {name: 'Details'}))

    await waitFor(() => expect((screen.getByLabelText('Authority ID') as HTMLInputElement).value).to.equal('PRIA'))
    const subsetInput = document.querySelector('.subset input') as HTMLInputElement

    expect((screen.getByLabelText('Authority name') as HTMLInputElement).value).to.equal('Agriculture Registry')
    expect((screen.getByLabelText('Registry code') as HTMLInputElement).value).to.equal('70006440')
    expect(subsetInput.value).to.equal('EU01')

    expect((screen.getByLabelText('Authority ID') as HTMLInputElement).disabled).to.equal(true)
    expect((screen.getByLabelText('Authority name') as HTMLInputElement).disabled).to.equal(true)
    expect((screen.getByLabelText('Registry code') as HTMLInputElement).disabled).to.equal(true)
    expect(subsetInput.disabled).to.equal(true)
    expect(screen.queryByRole('button', {name: '+'})).to.equal(null)
    expect(screen.queryAllByRole('button', {name: '×'}).length).to.equal(0)
    expect(screen.queryByRole('button', {name: 'Save'})).to.equal(null)

    expect(api.post).not.toHaveBeenCalled()
    expect(api.put).not.toHaveBeenCalled()
    expect(api.delete).not.toHaveBeenCalled()
  })
})
