import {expect, test} from '@playwright/test'

const user = {
  id: 'user-1',
  taraSub: 'EE12345678901',
  name: 'Test User',
  createdAt: new Date().toISOString(),
}

test.beforeEach(async ({page}) => {
  await page.route('**/auth/user', route => route.fulfill({json: user}))
  await page.route('**/admin/v1/gates/own', route => route.fulfill({json: null}))
  await page.route('**/admin/v1/gates', route => route.fulfill({json: []}))
})

test('loads the admin shell and navigates between sections', async ({page}) => {
  await page.addInitScript(() => sessionStorage.setItem('eftiJwt', 'test-token'))
  await page.goto('/gates')

  await expect(page.getByRole('link', {name: 'Gates'})).toBeVisible()
  await expect(page.getByRole('link', {name: 'Platforms'})).toBeVisible()

  await page.getByRole('link', {name: 'Platforms'}).click()
  await expect(page).toHaveURL(/\/platforms/)
})

test('redirects to TARA login when no session token is present', async ({page}) => {
  await page.route('**/tim/auth/login/tara**', route => route.fulfill({
    json: {authorization_url: 'https://tara-mock:8080/auth?client_id=efti'},
  }))

  await page.goto('/gates')

  await expect(page).toHaveURL(/\/tara\//)
})
