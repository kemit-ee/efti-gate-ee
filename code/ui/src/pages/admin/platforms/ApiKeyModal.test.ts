import {fireEvent, render} from '@testing-library/svelte'
import ApiKeyModal from './ApiKeyModal.svelte'
import type {PlatformApiKey} from 'src/api/ruuterTypes'

const key: PlatformApiKey = {id: 'mock', apiKey: 'secret-key-value', apiKeyHint: 'secr...', apiKeyGeneratedAt: '2026-01-01T00:00:00Z'}

describe('ApiKeyModal', () => {
  beforeEach(() => {
    Object.assign(navigator, {clipboard: {writeText: vi.fn().mockResolvedValue(undefined)}})
  })

  it('shows nothing when there is no result', () => {
    render(ApiKeyModal, {result: false})
    expect(document.body.classList.contains('modal-open')).to.be.false
  })

  it('shows the key and warning when a result is set', () => {
    render(ApiKeyModal, {result: key})
    expect(document.body.textContent).to.contain('secret-key-value')
    expect(document.body.textContent).to.contain('Copy this key now')
    expect(document.body.textContent).to.contain('mock')
  })

  it('copies the key to the clipboard and shows Copied', async () => {
    const {getByText} = render(ApiKeyModal, {result: key})

    await fireEvent.click(getByText('Copy'))

    expect(navigator.clipboard.writeText).toHaveBeenCalledWith('secret-key-value')
    expect(getByText('Copied')).to.exist
  })

  it('calls onClose and clears the result when the modal is closed', async () => {
    const onClose = vi.fn()
    const {getByTitle} = render(ApiKeyModal, {result: key, onClose})

    await fireEvent.click(getByTitle('Close'))

    expect(onClose).toHaveBeenCalled()
    expect(document.body.classList.contains('modal-open')).to.be.false
  })
})
