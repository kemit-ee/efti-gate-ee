import {render} from '@testing-library/svelte'
import QrCodeViewer from './QrCodeViewer.svelte'
import QRCode from 'qrcode'

vi.mock('qrcode', () => ({default: {toCanvas: vi.fn()}}))

describe('QrCodeViewer', () => {
  beforeEach(() => vi.clearAllMocks())

  it('renders a canvas', () => {
    const {container} = render(QrCodeViewer, {content: 'EE/mock/ds-1'})
    expect(container.querySelector('canvas')).to.exist
  })

  it('draws the QR code for the given content', () => {
    render(QrCodeViewer, {content: 'EE/mock/ds-1'})
    expect(QRCode.toCanvas).toHaveBeenCalledWith(
      expect.any(HTMLCanvasElement), 'EE/mock/ds-1', expect.objectContaining({width: 220}), expect.any(Function)
    )
  })

  it('does not draw when content is empty', () => {
    render(QrCodeViewer, {content: ''})
    expect(QRCode.toCanvas).not.toHaveBeenCalled()
  })
})
