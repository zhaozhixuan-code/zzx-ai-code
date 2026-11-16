import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { test } from 'node:test'
import vm from 'node:vm'
import { parse } from '@vue/compiler-sfc'
import ts from 'typescript'

// 执行页面实际的 SSE 回调，避免测试替身复制另一份完成/失败判断。
const source = readFileSync(new URL('../src/pages/app/AppChatPage.vue', import.meta.url), 'utf8')
const script = parse(source).descriptor.scriptSetup.content
const syntax = ts.createSourceFile('AppChatPage.ts', script, ts.ScriptTarget.Latest, true, ts.ScriptKind.TS)
const names = new Set(['generateCode', 'handleError'])
const functions = syntax.statements.filter(statement => ts.isVariableStatement(statement)
  && statement.declarationList.declarations.some(declaration => names.has(declaration.name.getText(syntax))))
assert.equal(functions.length, 2)
const executable = ts.transpileModule(functions.map(statement => statement.getText(syntax)).join('\n'), {
  compilerOptions: { target: ts.ScriptTarget.ES2022 },
}).outputText

/** 创建隔离页面状态和可手动触发事件的 EventSource；不访问后端或真实浏览器。
 * @returns 页面状态、定时回调和实际生成函数的独立测试环境。
 */
function fixture() {
  const instances = []
  class FakeEventSource {
    static CONNECTING = 0
    static OPEN = 1
    static CLOSED = 2
    listeners = new Map()
    readyState = FakeEventSource.OPEN
    closed = 0
    constructor() { instances.push(this) }
    /** @param {string} name SSE 事件名；@param {Function} callback 页面回调。 */
    addEventListener(name, callback) { this.listeners.set(name, callback) }
    /** 模拟关闭连接，记录次数以确认错误后不会保留自动重连连接。 */
    close() { this.closed++; this.readyState = FakeEventSource.CLOSED }
  }
  const state = {
    isChatPageActive: true, activeEventSource: null, EventSource: FakeEventSource,
    request: { defaults: { baseURL: 'http://127.0.0.1/api' } }, appId: { value: '11' },
    isGenerating: { value: true }, messages: { value: [{ content: '', loading: true }] },
    errors: [], timers: [], previewUpdates: 0, fetches: 0,
    URLSearchParams, console: { error() {} }, scrollToBottom() {},
  }
  state.message = { error: value => state.errors.push(value) }
  state.setTimeout = callback => state.timers.push(callback)
  state.fetchAppInfo = async () => { state.fetches++ }
  state.updatePreview = () => { state.previewUpdates++ }
  vm.createContext(state)
  vm.runInContext(`${executable}\nthis.runGeneration = generateCode`, state)
  return { state, instances }
}

test('断流等待重连和连接关闭都必须失败，禁止刷新预览或自动重复请求', async () => {
  for (const readyState of [0, 2]) {
    const { state, instances } = fixture()
    await state.runGeneration('page', 0)
    const stream = instances[0]
    stream.readyState = readyState
    stream.onerror()
    assert.equal(state.isGenerating.value, false)
    assert.equal(stream.closed, 1)
    assert.equal(state.activeEventSource, null)
    assert.equal(state.errors.length, 1)
    assert.equal(state.timers.length, 0)
    assert.equal(state.previewUpdates, 0)
    stream.onerror()
    assert.equal(state.errors.length, 1)
  }
})

test('只有 done 刷新预览，完成后的 error 不覆盖成功', async () => {
  const { state, instances } = fixture()
  await state.runGeneration('page', 0)
  const stream = instances[0]
  stream.onmessage({ data: JSON.stringify({ d: 'generated' }) })
  assert.equal(state.messages.value[0].content, 'generated')
  stream.listeners.get('done')()
  assert.equal(stream.closed, 1)
  assert.equal(state.timers.length, 1)
  await state.timers[0]()
  assert.equal(state.fetches, 1)
  assert.equal(state.previewUpdates, 1)
  stream.onerror()
  assert.equal(state.errors.length, 0)
})

test('JSON 解析失败与业务错误均关闭连接，后续 done 不能覆盖失败', async () => {
  for (const kind of ['message-json', 'business-json', 'business-error']) {
    const { state, instances } = fixture()
    await state.runGeneration('page', 0)
    const stream = instances[0]
    if (kind === 'message-json') stream.onmessage({ data: '{' })
    else stream.listeners.get('business-error')({ data: kind === 'business-json' ? '{' : JSON.stringify({ message: 'rate limited' }) })
    assert.equal(stream.closed, 1)
    assert.equal(state.isGenerating.value, false)
    assert.equal(state.errors.length, 1)
    stream.listeners.get('done')()
    assert.equal(state.timers.length, 0)
  }
})
