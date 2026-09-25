import {fireEvent, render, waitFor} from '@testing-library/svelte'
import PlatformList from './PlatformList.svelte'
import api from 'src/api/api'
import {type Platform, type PlatformApiKey, Status} from 'src/api/ruuterTypes'

vi.mock('src/api/api', () => ({default: {delete: vi.fn().mockResolvedValue(undefined), post: vi.fn()}}))

const platforms: Platform[] = [{id: 'mock', baseUrl: 'https://mock.example/api', status: Status.ONLINE, createdAt: '2026-01-01T00:00:00Z'}]

describe('PlatformList', () => {
  let onEdit: (platform: Platform) => void, onDeleted: (platform: Platform) => void, onChanged: () => void

  beforeEach(() => {
    vi.clearAllMocks()
    onEdit = vi.fn()
    onDeleted = vi.fn()
    onChanged = vi.fn()
    vi.spyOn(window, 'confirm').mockReturnValue(true)
  })

  it('renders a row per platform', () => {
    const {container} = render(PlatformList, {platforms, onEdit, onDeleted, onChanged})
    expect(container.textContent).to.contain('mock')
    expect(container.textContent).to.contain('https://mock.example/api')
    expect(container.textContent).to.contain('No key')
  })

  it('calls onEdit with the platform when Edit is clicked', async () => {
    const {getByText} = render(PlatformList, {platforms, onEdit, onDeleted, onChanged})
    await fireEvent.click(getByText('Edit'))
    expect(onEdit).toHaveBeenCalledWith(platforms[0])
  })

  it('deletes the platform after confirmation and calls onDeleted', async () => {
    const {getByText} = render(PlatformList, {platforms, onEdit, onDeleted, onChanged})
    await fireEvent.click(getByText('Delete'))
    expect(api.delete).toHaveBeenCalledWith('platforms/mock')
    expect(onDeleted).toHaveBeenCalledWith(platforms[0])
  })

  it('generates an API key and shows it in the modal', async () => {
    const key: PlatformApiKey = {id: 'mock', apiKey: 'secret-key-value', apiKeyHint: 'secr...', apiKeyGeneratedAt: '2026-01-01T00:00:00Z'}
    vi.mocked(api.post).mockResolvedValue(key)
    const {getByText} = render(PlatformList, {platforms, onEdit, onDeleted, onChanged})

    await fireEvent.click(getByText('Generate API key'))

    await waitFor(() => expect(api.post).toHaveBeenCalledWith('platforms/api-key/mock'))
    await waitFor(() => expect(document.body.textContent).to.contain('secret-key-value'))
  })

  it('asks for confirmation before regenerating an existing API key', async () => {
    const withKey = [{...platforms[0], hasApiKey: true}]
    vi.spyOn(window, 'confirm').mockReturnValue(false)
    const {getByText} = render(PlatformList, {platforms: withKey, onEdit, onDeleted, onChanged})

    await fireEvent.click(getByText('Generate API key'))

    expect(window.confirm).toHaveBeenCalled()
    expect(api.post).not.toHaveBeenCalled()
  })
})
