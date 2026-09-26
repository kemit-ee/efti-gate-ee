import {t} from 'i18n'
import {handleUnhandledRejection, jsErrorHandler, reportError, initErrorHandlers} from './errorHandlers'
import {get} from 'svelte/store'
import {toastStore} from 'src/stores/toasts'
import api from './api'

vi.mock('./api', () => ({default: {post: vi.fn().mockResolvedValue(undefined)}}))

describe('handles unhandled promises', () => {
  let promise: Promise<any>

  function PromiseRejectionEvent(init: PromiseRejectionEventInit) {
    return Object.assign(new CustomEvent('unhandledrejection', init), init) as PromiseRejectionEvent
  }

  beforeEach(() => {
    promise = Promise.reject('')
  })

  afterEach(() => {
    toastStore.set([])
    promise.catch(() => {})
  })

  it('without translation', async () => {
    const e = PromiseRejectionEvent({reason: {message: 'no translation'}, promise})
    handleUnhandledRejection(e)
    expect(get(toastStore).last().message).to.eq('no translation')
  })

  it('with translation', async () => {
    const e = PromiseRejectionEvent({reason: {message: 'errors.technical', statusCode: 500}, promise})
    handleUnhandledRejection(e)
    expect(get(toastStore).last().message).to.eq(t.errors.technical)
  })

  it('routes a genuine Error (with a stack) through jsErrorHandler instead of a toast', () => {
    vi.spyOn(window, 'alert').mockImplementation(() => {})
    const error = new Error('crashed')
    const e = PromiseRejectionEvent({reason: error, promise})

    handleUnhandledRejection(e)

    expect(window.alert).toHaveBeenCalled()
  })

  it('shows a generic technical-error toast when the rejection has no message at all', () => {
    const e = PromiseRejectionEvent({reason: undefined, promise})
    handleUnhandledRejection(e)
    expect(get(toastStore).last().message).to.contain(t.errors.technical)
  })
})

describe('jsErrorHandler', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(api.post).mockResolvedValue(undefined)
    vi.spyOn(window, 'alert').mockImplementation(() => {})
  })

  it('reports the error and alerts with a generic message', () => {
    jsErrorHandler('boom', 'app.js', 1, 2)

    expect(window.alert).toHaveBeenCalledWith(expect.stringContaining('Technical error occurred'))
    expect(api.post).toHaveBeenCalled()
  })

  it('alerts with an outdated-browser message for a SyntaxError', () => {
    jsErrorHandler('boom', 'app.js', 1, 2, {name: 'SyntaxError'} as Error)

    expect(window.alert).toHaveBeenCalledWith(expect.stringContaining('too old'))
  })
})

describe('reportError', () => {
  beforeEach(() => vi.clearAllMocks())

  it('posts the error with page context, swallowing its own failures', async () => {
    vi.mocked(api.post).mockRejectedValue(new Error('reporting endpoint down'))

    reportError({message: 'boom'}, new Error('boom'))

    await vi.waitFor(() => expect(api.post).toHaveBeenCalledWith('js-error', expect.objectContaining({
      message: 'boom', href: expect.any(String), userAgent: expect.any(String), stack: expect.any(String),
    })))
  })
})

describe('initErrorHandlers', () => {
  it('wires up the global error and rejection handlers', () => {
    const addEventListener = vi.spyOn(window, 'addEventListener')

    initErrorHandlers()

    expect(window.onerror).to.equal(jsErrorHandler)
    expect(addEventListener).toHaveBeenCalledWith('unhandledrejection', handleUnhandledRejection)
    expect(addEventListener).toHaveBeenCalledWith('vite:preloadError', expect.any(Function))
  })
})
