import api, {getToken} from 'src/api/api'

vi.mock('src/api/api', () => ({default: {get: vi.fn(), post: vi.fn()}, getToken: vi.fn()}))

describe('main', () => {
  it('mounts the app into #app and installs the global error handlers', async () => {
    vi.mocked(getToken).mockReturnValue(null)
    vi.spyOn(window, 'fetch').mockResolvedValue({json: () => Promise.resolve({authorization_url: 'https://tara-mock:8080/oidc/authorize'})} as any)
    window.matchMedia = vi.fn().mockImplementation((query: string) => ({
      matches: false, media: query, addEventListener: vi.fn(), removeEventListener: vi.fn()
    })) as any

    const app = document.createElement('div')
    app.id = 'app'
    document.body.appendChild(app)

    await import('./main')

    expect(app.children.length).to.be.greaterThan(0)
    expect(window.onerror).to.be.a('function')

    document.body.removeChild(app)
  })
})
