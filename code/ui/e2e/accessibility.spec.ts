import AxeBuilder from '@axe-core/playwright'
import {expect, test} from '@playwright/test'

// MFN-12 / WCAG 2.2 AA. One row of realistic mock data per list page, not empty lists, so
// table/row/action-button markup is actually exercised -- an empty table hides most real a11y issues.
const user = {
  id: 'user-1',
  taraSub: 'EE12345678901',
  name: 'Test User',
  createdAt: new Date().toISOString(),
}

const gate = {
  id: 'EU-EE',
  countryCode: 'EE',
  eDeliveryUrl: 'https://gate-ee.example/msh',
  status: 'ONLINE',
}

const platform = {
  id: 'mock',
  baseUrl: 'https://mock.example/api',
  status: 'ONLINE',
  hasApiKey: true,
}

const authority = {
  id: 'auth-1',
  name: 'Test Authority',
  registryCode: '70000093',
  subsets: ['EU01', 'EU02'],
}

const consignment = {
  datasetId: 'b0284c81-a79d-11f1-931a-4ca954ebfb9f',
  platformId: 'mock',
  gateId: 'EU-EE',
  status: 'ACTIVE',
  xml: '<ParameterIDSetCriteria/>',
  dangerousGoods: 'false',
  createdAt: new Date().toISOString(),
  usedEquipmentIds: [],
  carriedEquipmentIds: [],
  mainTransportId: 'MOCK-123',
}

test.beforeEach(async ({page}) => {
  await page.addInitScript(() => sessionStorage.setItem('eftiJwt', 'test-token'))
  await page.route('**/auth/user', route => route.fulfill({json: user}))
  await page.route('**/admin/v1/gates/own', route => route.fulfill({json: gate}))
  await page.route('**/admin/v1/gates*', route => route.fulfill({json: [gate]}))
  await page.route('**/admin/v1/platforms*', route => route.fulfill({json: [platform]}))
  await page.route('**/admin/v1/authorities*', route => route.fulfill({json: [authority]}))
  await page.route('**/admin/v1/users*', route => route.fulfill({json: [user]}))
  await page.route('**/admin/v1/consignments*', route => route.fulfill({json: [consignment]}))
})

const pages: Array<[name: string, path: string]> = [
  ['gates', '/gates'],
  ['platforms', '/platforms'],
  ['authorities', '/authorities'],
  ['users', '/users'],
  ['consignments', '/consignments'],
]

for (const [name, path] of pages) {
  test(`${name} page has no WCAG 2.0/2.1/2.2 AA violations`, async ({page}) => {
    await page.goto(path)
    await expect(page.getByRole('link', {name: 'Gates'})).toBeVisible()

    const results = await new AxeBuilder({page})
      .withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa', 'wcag22aa'])
      .analyze()

    expect(results.violations, JSON.stringify(results.violations, null, 2)).toEqual([])
  })
}
