// 子项目B 设备数据中心 — 浏览器冒烟
// 前置：TDengine 已造数（Task 1）；vite :25173 + 网关 :8000 运行。
// 依赖：playwright-core 可解析（本机已装）；Edge 无头。
import { chromium } from 'playwright-core'

const BASE = 'http://127.0.0.1:25173'
const EDGE = 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe'
const DEVICE_NAME = 'sim-dev-000001'
const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

let passed = 0, failed = 0
const fails = []
function ok(name, cond, detail = '') {
  if (cond) { passed++; console.log('PASS  ' + name) }
  else { failed++; fails.push(name + (detail ? ' — ' + detail : '')); console.log('FAIL  ' + name + (detail ? ' — ' + detail : '')) }
}

const browser = await chromium.launch({ executablePath: EDGE, headless: true })
const page = await (await browser.newContext({ viewport: { width: 1600, height: 1000 } })).newPage()
page.setDefaultTimeout(15000)

try {
  // 1. 登录 → 设备管理
  await page.goto(`${BASE}/login`)
  await page.click('button:has-text("登 录")')
  await page.waitForURL(/dashboard|archive|ems|system|product|device|alarm|shadow|command/, { timeout: 20000 })
  await page.waitForSelector('.el-menu', { timeout: 10000 })
  ok('1. 登录成功进入主界面', true)
  await page.click('.el-menu-item:has-text("设备管理")')
  await page.waitForURL(/device/, { timeout: 10000 })
  await page.waitForSelector('.table-card', { timeout: 10000 })
  ok('2. 进入 /device 设备管理页', page.url().includes('/device'))

  // 2. 按名称搜索 sim-dev-000001 并打开详情
  await page.fill('.filter-card input[placeholder="设备名模糊"]', DEVICE_NAME)
  await page.click('button:has-text("查询")')
  await page.waitForSelector(`.el-table__row:has-text("${DEVICE_NAME}")`, { timeout: 10000 })
  ok('3. 按名称找到 sim-dev-000001', true)
  await page.locator(`.el-table__row:has-text("${DEVICE_NAME}")`).click()
  await page.waitForSelector('.el-drawer', { state: 'visible' })
  const box = await page.locator('.el-drawer').boundingBox()
  ok('4. 抽屉加宽至 820px', !!box && box.width >= 800, `width=${box?.width}`)
  const runtimeTab = page.locator('.el-tabs__item:has-text("运行状态")')
  ok('5. 出现「运行状态」tab', (await runtimeTab.count()) > 0)

  // 3. 切到运行状态 → 最新值卡片
  await runtimeTab.click()
  await page.waitForSelector('.rt-card', { timeout: 10000 })
  await page.waitForFunction(() => Array.from(document.querySelectorAll('.rt-card')).some((c) => c.textContent.includes('soc')), null, { timeout: 10000 })
  const socCard = await page.locator('.rt-card:has-text("soc")').textContent()
  ok('6. 卡片区含 soc 且值非 —', !!socCard && !socCard.includes('—'), socCard)
  const headText = await page.locator('.runtime-head').textContent()
  ok('7. 最后上报时间非空', /最后上报：\s*\d{2}-\d{2}/.test(headText), headText)

  // 4. 历史查询：默认近24h + 默认属性 → 折线图 + 表格
  await page.click('.hist-controls button:has-text("查询")')
  await page.waitForSelector('.hist-chart canvas', { timeout: 15000 })
  await page.waitForSelector('.hist-card .el-table__row', { timeout: 15000 })
  ok('8. 折线图 canvas 已渲染', (await page.locator('.hist-chart canvas').count()) > 0)
  const rowCount = await page.locator('.hist-card .el-table__row').count()
  ok('9. 数据表格 ≥1 行', rowCount >= 1, `rows=${rowCount}`)

  // 5. 翻页（总行数 > 20 时第 2 页仍有数据）
  const totalText = await page.locator('.hist-card .el-pagination__total').textContent()
  const totalMatch = totalText?.match(/\d+/)
  if (totalMatch && Number(totalMatch[0]) > 20) {
    await page.click('.hist-card .el-pager li:nth-child(2)')
    await page.waitForTimeout(600)
    const p2 = await page.locator('.hist-card .el-table__row').count()
    ok('10. 翻到第 2 页仍有数据', p2 >= 1, `rows=${p2}`)
  } else {
    ok('10. 翻页（总行数不足 20，跳过断言）', true)
  }

  // 6. 切到未来时间窗（无数据）→ 空态
  const fmt = (d) => { const p = (n) => String(n).padStart(2, '0'); return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}T${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}` }
  const f1 = fmt(new Date(Date.now() + 3600000))
  const f2 = fmt(new Date(Date.now() + 7200000))
  const inputs = page.locator('.hist-controls .el-range-input')
  await inputs.nth(0).fill(f1)
  await inputs.nth(1).fill(f2)
  await inputs.nth(1).press('Enter')
  await page.click('.hist-controls button:has-text("查询")')
  await page.waitForSelector('.hist-empty', { timeout: 10000 })
  ok('11. 无数据时间窗显示空态', true)
} catch (e) {
  failed++; fails.push('脚本异常: ' + (e?.message || String(e)))
  console.log('ERROR ' + (e?.stack || e))
}

console.log(`\n=== ${passed} PASS / ${failed} FAIL ===`)
await browser.close()
process.exit(failed > 0 ? 1 : 0)
