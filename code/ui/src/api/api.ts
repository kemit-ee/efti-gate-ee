import {t} from 'i18n'

export const headers = {'Content-Type': 'application/json; charset=UTF-8', 'Accept': 'application/json'} as HeadersInit

const TOKEN_KEY = 'eftiJwt'

export function getToken(): string | null {
  return sessionStorage.getItem(TOKEN_KEY)
}

export function setToken(token: string) {
  sessionStorage.setItem(TOKEN_KEY, token)
}

export function clearToken() {
  sessionStorage.removeItem(TOKEN_KEY)
}

type Body = object|string|FormData|File

class Api {
  constructor(public prefix = '/admin/v1/') {}

  private authHeaders(): Record<string, string> {
    const token = getToken()
    return token ? {'Authorization': 'Bearer ' + token} : {}
  }

  request(path: string, init?: RequestInit | {body?: Body, headers?: HeadersInit}): Promise<Response> {
    if (!path.startsWith('/')) path = this.prefix + path
    document.documentElement.classList.add('loading')
    const disabledButtons = this.disableSubmitButtons((init as RequestInit)?.method)

    const body = init?.body
    const mergedHeaders = {...headers, ...this.authHeaders(), ...(init?.headers ?? {})}
    return fetch(path, {
      ...init,
      body: body instanceof File || body instanceof FormData || typeof body == 'string' ? body : body && JSON.stringify(body),
      headers: mergedHeaders
    })
    .catch(this.handleFetchFailure)
    .finally(() => {
      document.documentElement.classList.remove('loading')
      disabledButtons?.forEach(btn => btn.disabled = false)
    }) as Promise<Response>
  }

  requestJson(path: string, init?: RequestInit | {body?: Body, headers?: HeadersInit}) {
    return this.request(path, init).then(this.extractJsonHandlingErrors)
  }

  requestXml(path: string, init?: RequestInit | { body?: Body; headers?: HeadersInit}) {
    return this.request(path, init)
      .then(async response => {
        const text = await response.text()
        if (!response.ok) {
          let message = text
          try {
            const json = JSON.parse(text)
            message = json.message
          } catch (e) {
            message ||= t.errors.technical
          }
          throw {message, response: text}
        }
        return text
      })
  }


  private async extractJsonHandlingErrors(response: Response): Promise<any> {
    let data: any
    try {
      data = response.status == 204 ? await response.text(): await response.json()
    } catch (e) {
      console.error('Not a JSON', e)
      throw {message: 'errors.notJson'}
    }
    const apiVersion = response.headers?.get('x-api-version')
    if (response.status < 200 || response.status >= 400 || data.error) {
      data.message = data.message || data.error || data.detail || data.title
      throw data
    } else if (apiVersion && apiVersion != window.apiVersion) {
      location.reload()
    }
    return data
  }

  private handleFetchFailure(error: any) {
    if (error.message === 'Failed to fetch' || error.message === 'Load failed' || error.errno) throw {message: 'errors.networkUnavailable'}
    else throw error
  }

  async get<T>(path: string, headers?: HeadersInit): Promise<T> {
    return await this.requestJson(path, {headers}) as T
  }

  post<T>(path: string, body?: Body, headers?: HeadersInit): Promise<T> {
    return this.requestJson(path, {method: 'POST', body, headers})
  }

  put<T>(path: string, body?: Body, headers?: HeadersInit): Promise<T> {
    return this.requestJson(path, {method: 'PUT', body, headers})
  }

  delete(path: string) {
    return this.requestJson(path, {method: 'DELETE'})
  }

  patch(path: string, body: object) {
    return this.requestJson(path, {method: 'PATCH', body})
  }

  disableSubmitButtons(method?: string) {
    if (method === 'GET') return
    const buttons = document.querySelectorAll<HTMLButtonElement>("form button:not(:disabled)")
    buttons.forEach(btn => btn.disabled = true)
    return buttons
  }
}

export default new Api()
