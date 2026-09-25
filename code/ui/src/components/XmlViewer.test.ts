import {render} from '@testing-library/svelte'
import XmlViewer from './XmlViewer.svelte'

describe('XmlViewer', () => {
  it('pretty-prints a nested xml document', () => {
    const {container} = render(XmlViewer, {content: '<Root><Child>text</Child></Root>'})
    expect(container.textContent).to.contain('<Root>')
    expect(container.textContent).to.contain('<Child>text</Child>')
    expect(container.textContent).to.contain('</Root>')
  })

  it('self-closes empty elements', () => {
    const {container} = render(XmlViewer, {content: '<Root><Empty></Empty></Root>'})
    expect(container.textContent).to.contain('<Empty/>')
  })

  it('preserves attributes', () => {
    const {container} = render(XmlViewer, {content: '<Root id="1"><Child/></Root>'})
    expect(container.textContent).to.contain('id="1"')
  })

  it('falls back to the raw content when parsing fails', () => {
    const {container} = render(XmlViewer, {content: '<Root><Unclosed></Root>'})
    expect(container.textContent).to.contain('<Root><Unclosed></Root>')
  })
})
