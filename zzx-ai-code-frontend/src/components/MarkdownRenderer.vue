<template>
  <div ref="markdownRoot" class="markdown-content" v-html="renderedMarkdown"></div>
</template>

<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import MarkdownIt from 'markdown-it'
import hljs from 'highlight.js'

// 引入代码高亮样式
import 'highlight.js/styles/github.css'

interface Props {
  content: string
}

const props = defineProps<Props>()
const markdownRoot = ref<HTMLElement>()
const expandedCodeBlocks = new Set<number>()
const COLLAPSED_CODE_LINE_COUNT = 12

// 配置 markdown-it 实例
const md: MarkdownIt = new MarkdownIt({
  html: true,
  linkify: true,
  typographer: true,
  highlight: function (str: string, lang: string): string {
    if (lang && hljs.getLanguage(lang)) {
      try {
        return (
          '<pre class="hljs"><code>' +
          hljs.highlight(str, { language: lang, ignoreIllegals: true }).value +
          '</code></pre>'
        )
      } catch {
        // 忽略错误，使用默认处理
      }
    }

    return '<pre class="hljs"><code>' + md.utils.escapeHtml(str) + '</code></pre>'
  },
})

/**
 * 渲染代码围栏；长代码默认折叠，并按消息内代码块序号保留用户展开状态。
 * @param tokens 当前 Markdown 的 token 列表。
 * @param index 当前代码围栏 token 的位置。
 * @returns 高亮后的代码 HTML，长代码外层包含原生可访问的折叠控件。
 */
md.renderer.rules.fence = (tokens, index) => {
  const token = tokens[index]
  const code = token.content
  const lines = code.replace(/\n$/, '').split('\n').length
  const language = md.utils.escapeHtml(token.info.trim().split(/\s+/, 1)[0] || '代码')
  let highlighted = ''

  if (token.info && hljs.getLanguage(token.info.trim().split(/\s+/, 1)[0])) {
    try {
      highlighted = hljs.highlight(code, {
        language: token.info.trim().split(/\s+/, 1)[0],
        ignoreIllegals: true,
      }).value
    } catch {
      highlighted = md.utils.escapeHtml(code)
    }
  } else {
    highlighted = md.utils.escapeHtml(code)
  }

  const codeHtml = `<pre class="hljs"><code>${highlighted}</code></pre>`
  if (lines < COLLAPSED_CODE_LINE_COUNT) return codeHtml

  const expanded = expandedCodeBlocks.has(index) ? ' open' : ''
  return `<details class="code-fold" data-code-block="${index}"${expanded}><summary><span class="code-language">${language}</span><span class="code-line-count">${lines} 行</span><span class="code-fold-open">展开代码</span><span class="code-fold-close">收起代码</span></summary>${codeHtml}</details>`
}

/** 记录原生 details 的展开切换，使流式消息重绘后仍保持用户当前选择。 */
function rememberCodeFoldState(event: Event): void {
  const details = event.target
  if (!(details instanceof HTMLDetailsElement) || !details.matches('.code-fold')) return
  const index = Number(details.dataset.codeBlock)
  if (!Number.isSafeInteger(index)) return
  if (details.open) expandedCodeBlocks.add(index)
  else expandedCodeBlocks.delete(index)
}

onMounted(() => markdownRoot.value?.addEventListener('toggle', rememberCodeFoldState, true))
onUnmounted(() => markdownRoot.value?.removeEventListener('toggle', rememberCodeFoldState, true))

// 计算渲染后的 Markdown
const renderedMarkdown = computed(() => {
  return md.render(props.content)
})
</script>

<style scoped>
.markdown-content {
  line-height: 1.6;
  color: var(--color-text);
  word-wrap: break-word;
}

/* 全局样式，影响 v-html 内容 */
.markdown-content :deep(h1),
.markdown-content :deep(h2),
.markdown-content :deep(h3),
.markdown-content :deep(h4),
.markdown-content :deep(h5),
.markdown-content :deep(h6) {
  margin: 1.5em 0 0.5em 0;
  font-weight: 600;
  line-height: 1.25;
}

.markdown-content :deep(h1) {
  font-size: 1.5em;
  border-bottom: 1px solid var(--color-border);
  padding-bottom: 0.3em;
}

.markdown-content :deep(h2) {
  font-size: 1.3em;
  border-bottom: 1px solid var(--color-border);
  padding-bottom: 0.3em;
}

.markdown-content :deep(h3) {
  font-size: 1.1em;
}

.markdown-content :deep(p) {
  margin: 0.8em 0;
}

.markdown-content :deep(ul),
.markdown-content :deep(ol) {
  margin: 0.8em 0;
  padding-left: 1.5em;
}

.markdown-content :deep(li) {
  margin: 0.3em 0;
}

.markdown-content :deep(blockquote) {
  margin: 1em 0;
  padding: 0.5em 1em;
  border-left: 4px solid var(--color-border-strong);
  background-color: var(--color-surface-muted);
  color: var(--color-text-secondary);
}

.markdown-content :deep(code) {
  background-color: var(--color-surface-muted);
  padding: 0.2em 0.4em;
  border-radius: 3px;
  font-family: 'Monaco', 'Menlo', 'Ubuntu Mono', monospace;
  font-size: 0.9em;
}

.markdown-content :deep(pre) {
  background-color: var(--color-surface-muted);
  border: 1px solid var(--color-border);
  border-radius: 6px;
  padding: 1em;
  overflow-x: auto;
  margin: 1em 0;
}

.markdown-content :deep(.code-fold) {
  margin: 1em 0;
  overflow: hidden;
  border: 1px solid var(--color-border);
  border-radius: 6px;
  background: var(--color-surface-muted);
}

.markdown-content :deep(.code-fold summary) {
  display: flex;
  min-height: 38px;
  align-items: center;
  gap: 10px;
  padding: 0 12px;
  color: var(--color-text-secondary);
  cursor: pointer;
  font-size: 12px;
  list-style: none;
}

.markdown-content :deep(.code-fold summary::-webkit-details-marker) {
  display: none;
}

.markdown-content :deep(.code-language) {
  color: var(--color-primary);
  font-weight: 600;
}

.markdown-content :deep(.code-line-count) {
  color: var(--color-text-muted);
  font-variant-numeric: tabular-nums;
}

.markdown-content :deep(.code-fold-open),
.markdown-content :deep(.code-fold-close) {
  margin-left: auto;
  color: var(--color-primary);
}

.markdown-content :deep(.code-fold-close),
.markdown-content :deep(.code-fold[open] .code-fold-open) {
  display: none;
}

.markdown-content :deep(.code-fold[open] .code-fold-close) {
  display: inline;
}

.markdown-content :deep(.code-fold summary:focus-visible) {
  outline: 3px solid var(--color-focus);
  outline-offset: -3px;
}

.markdown-content :deep(.code-fold pre.hljs) {
  margin: 0;
  border: 0;
  border-top: 1px solid var(--color-border);
  border-radius: 0;
}

.markdown-content :deep(.code-fold[open] pre.hljs) {
  max-height: min(55vh, 480px);
  overflow: auto;
}

.markdown-content :deep(pre code) {
  background-color: transparent;
  padding: 0;
  border-radius: 0;
  font-size: 0.9em;
  line-height: 1.4;
}

.markdown-content :deep(table) {
  border-collapse: collapse;
  margin: 1em 0;
  width: 100%;
}

.markdown-content :deep(table th),
.markdown-content :deep(table td) {
  border: 1px solid var(--color-border-strong);
  padding: 0.5em 0.8em;
  text-align: left;
}

.markdown-content :deep(table th) {
  background-color: var(--color-surface-muted);
  font-weight: 600;
}

.markdown-content :deep(table tr:nth-child(even)) {
  background-color: var(--color-surface-muted);
}

.markdown-content :deep(a) {
  color: var(--color-primary);
  text-decoration: none;
}

.markdown-content :deep(a:hover) {
  text-decoration: underline;
}

.markdown-content :deep(img) {
  max-width: 100%;
  height: auto;
  border-radius: 4px;
  margin: 0.5em 0;
}

.markdown-content :deep(hr) {
  border: none;
  border-top: 1px solid var(--color-border);
  margin: 1.5em 0;
}

/* 代码高亮样式优化 */
.markdown-content :deep(.hljs) {
  background-color: var(--color-surface-muted) !important;
  border-radius: 6px;
  font-family: 'Monaco', 'Menlo', 'Ubuntu Mono', monospace;
  font-size: 0.9em;
  line-height: 1.4;
}

/* 特定语言的代码块样式 */
.markdown-content :deep(.hljs-keyword) {
  color: #d73a49;
  font-weight: 600;
}

.markdown-content :deep(.hljs-string) {
  color: #032f62;
}

.markdown-content :deep(.hljs-comment) {
  color: #6a737d;
  font-style: italic;
}

.markdown-content :deep(.hljs-number) {
  color: #005cc5;
}

.markdown-content :deep(.hljs-function) {
  color: #6f42c1;
}

.markdown-content :deep(.hljs-tag) {
  color: #22863a;
}

.markdown-content :deep(.hljs-attr) {
  color: #6f42c1;
}

.markdown-content :deep(.hljs-title) {
  color: #6f42c1;
  font-weight: 600;
}
</style>
