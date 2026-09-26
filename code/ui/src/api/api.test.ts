import api, {headers, getToken, setToken, clearToken} from './api'
import type {MockInstance} from 'vitest'

const successfulResponse = {status: 200, headers: {get: () => undefined}, json: () => 'data'} as any

describe('api', () => {
  let fetch: MockInstance

  beforeEach(() => {
    fetch = vi.spyOn(window, 'fetch').mockResolvedValue(successfulResponse)
    clearToken()
  })

  it('extracts json', async () => {
    const promise = api.requestJson('path', {body: {data: 'data'}})
    expect(document.documentElement.classList.contains('loading')).to.equal(true)
    expect(fetch).toHaveBeenCalledWith('/admin/v1/path', {headers: {...headers}, body: '{"data":"data"}'})
    expect(await promise).to.equal('data')
    expect(document.documentElement.classList.contains('loading')).to.equal(false)
  })

  it('sends Authorization header when token is set', async () => {
    setToken('test-jwt')
    await api.requestJson('path', {body: {data: 'data'}})
    expect(fetch).toHaveBeenCalledWith('/admin/v1/path', {
      headers: {...headers, 'Authorization': 'Bearer test-jwt'},
      body: '{"data":"data"}'
    })
  })

  it('does not send Authorization header when no token', async () => {
    await api.requestJson('path', {body: {data: 'data'}})
    const callHeaders = fetch.mock.calls[0][1].headers
    expect(callHeaders).to.not.have.property('Authorization')
  })

  it('refreshes on next navigate if version mismatch', async () => {
    window.apiVersion = '2.3'
    window.location = {reload: vi.fn()} as unknown as Location
    fetch.mockResolvedValue({...successfulResponse, headers: {get: () => '2.2'}})
    await api.requestJson('path', {body: {data: 'data'}})
    expect(location.reload).toHaveBeenCalled()
    window.apiVersion = undefined
  })

  it('supports null json response', async () => {
    fetch.mockResolvedValue({...successfulResponse, json: () => null})
    const promise = api.requestJson('path', {body: {data: 'data'}})
    expect(await promise).to.be.null
  })

  it('supports No Content response', async () => {
    fetch.mockResolvedValue({...successfulResponse, status: 204, text: () => ''})
    const promise = api.requestJson('path', {body: {data: 'data'}})
    expect(await promise).to.eq('')
  })

  it('handles http error', () => {
    fetch.mockResolvedValue({status: 403, json: () => Promise.resolve({statusCode: 403, message: 'Forbidden', reason: 'Forbidden'})})
    return api.requestJson('path', {headers}).then(() => {
      throw 'should be rejected'
    }, e => {
      expect(e).to.deep.equal({statusCode: 403, message: 'Forbidden', reason: 'Forbidden'})
      expect(document.documentElement.classList.contains('loading')).to.be.false
    })
  })

  describe('requests', () => {
    beforeEach(() => {
      vi.spyOn(api, 'requestJson')
    })

    it('get', async () => {
      api.get('path')
      expect(api.requestJson).toHaveBeenCalledWith('path', {headers: undefined})
    })

    it('post', () => {
      api.post('path', {data: 'data'}, {header1: 'val'})
      expect(api.requestJson).toHaveBeenCalledWith('path', {method: 'POST', body: {data: 'data'}, headers: {header1: 'val'}})
    })

    it('delete', () => {
      api.delete('path')
      expect(api.requestJson).toHaveBeenCalledWith('path', {method: 'DELETE'})
    })

    it('patch', () => {
      api.patch('path', {data: 'data'})
      expect(api.requestJson).toHaveBeenCalledWith('path', {method: 'PATCH', body: {data: 'data'}})
    })
  })

  it('gives a specific error when failed to parse JSON', () => {
    fetch.mockResolvedValue({
      json: () => {
        throw 'Invalid json'
      }
    })
    return api.requestJson('path', undefined).then(() => {
      throw 'should be rejected'
    }, e => {
      expect(e).to.deep.equal({message: 'errors.notJson'})
    })
  })

  it('gives a network error', () => {
    fetch.mockRejectedValue(new Error('Failed to fetch'))
    return api.requestJson('path').then(() => {
      throw 'should be rejected'
    }, e => {
      expect(e).to.deep.equal({message: 'errors.networkUnavailable'})
    })
  })

  describe('disabling form buttons on submit', () => {
    let form: HTMLFormElement, button: HTMLButtonElement

    beforeEach(() => {
      form = document.createElement('form')
      button = document.createElement('button')
      form.appendChild(button)
      document.body.appendChild(form)
    })

    it('disable any form button', async () => {
      const promise = api.requestJson('path', {method: 'POST'})
      expect(button.disabled).to.be.true
      await promise
      expect(button.disabled).to.be.false
    })

    it('does not disable anything for GET', async () => {
      const promise = api.requestJson('path', {method: 'GET'})
      expect(button.disabled).to.be.false
      await promise
      expect(button.disabled).to.be.false
    })

    it('does not enable disabled buttons', async () => {
      const disabledButton = document.createElement('button')
      disabledButton.disabled = true
      form.appendChild(disabledButton)

      const promise = api.requestJson('path', {method: 'PATCH'})
      expect(disabledButton.disabled).to.be.true
      await promise
      expect(disabledButton.disabled).to.be.true
    })

    it('enable button even if it is removed from DOM', async () => {
      const promise = api.requestJson('path', {method: 'DELETE'})
      expect(button.disabled).to.be.true
      form.removeChild(button)
      await promise
      expect(button.disabled).to.be.false
    })
  })
})

describe('token storage', () => {
  afterEach(() => sessionStorage.clear())

  it('stores, reads and clears the token', () => {
    expect(getToken()).to.equal(null)
    setToken('jwt-token')
    expect(getToken()).to.equal('jwt-token')
    clearToken()
    expect(getToken()).to.equal(null)
  })
})

describe('Api path/body/error handling not covered above', () => {
  function mockFetch(response: Partial<Response> | Promise<Response>) {
    return vi.spyOn(window, 'fetch').mockImplementation(() => response instanceof Promise ? response : Promise.resolve(response as Response))
  }

  beforeEach(() => {
    vi.restoreAllMocks()
    clearToken()
  })

  it('prefixes relative paths but leaves absolute paths untouched', async () => {
    const fetch = mockFetch({ok: true, status: 200, json: async () => ({})} as Response)
    await api.get('gates')
    expect(fetch.mock.calls[0][0]).to.equal('/admin/v1/gates')

    await api.get('/auth/user')
    expect(fetch.mock.calls[1][0]).to.equal('/auth/user')
  })

  it('serializes a plain object body as JSON but passes strings/FormData/File through as-is', async () => {
    const fetch = mockFetch({ok: true, status: 200, json: async () => ({})} as Response)

    await api.post('gates', {id: 'EE'})
    expect((fetch.mock.calls[0][1] as RequestInit).body).to.equal('{"id":"EE"}')

    await api.post('gates', 'raw-string')
    expect((fetch.mock.calls[1][1] as RequestInit).body).to.equal('raw-string')

    const form = new FormData()
    await api.post('gates', form)
    expect((fetch.mock.calls[2][1] as RequestInit).body).to.equal(form)
  })

  it('throws even on a 2xx response that carries an error field', async () => {
    mockFetch({ok: true, status: 200, json: async () => ({error: 'MISSING_SUBSET'})} as Response)
    await expect(api.get('gates')).rejects.toMatchObject({message: 'MISSING_SUBSET'})
  })

  it('exposes put as a JSON request too', async () => {
    const fetch = mockFetch({ok: true, status: 200, json: async () => ({saved: true})} as Response)
    expect(await api.put('gates/EE', {id: 'EE'})).to.deep.equal({saved: true})
    expect(fetch.mock.calls[0][1]).to.include({method: 'PUT'})
  })

  it('requestXml resolves with the raw text on a successful response', async () => {
    mockFetch({ok: true, status: 200, text: async () => '<Response/>'} as Response)
    expect(await api.requestXml('efti/dataset-xml')).to.equal('<Response/>')
  })

  it('requestXml extracts the message from a JSON error body', async () => {
    mockFetch({ok: false, status: 502, text: async () => '{"message":"upstream unavailable"}'} as Response)
    await expect(api.requestXml('efti/dataset-xml')).rejects.toMatchObject({message: 'upstream unavailable'})
  })

  it('requestXml falls back to a generic technical error for a non-JSON error body', async () => {
    mockFetch({ok: false, status: 502, text: async () => ''} as Response)
    await expect(api.requestXml('efti/dataset-xml')).rejects.toMatchObject({message: 'Technical error, please try again'})
  })
})
