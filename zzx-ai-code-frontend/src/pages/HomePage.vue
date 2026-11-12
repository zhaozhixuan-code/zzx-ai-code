<template>
  <div id="homePage">
    <div class="home-container">
      <header class="hero-section">
        <h1>今天，想做一个什么应用？</h1>
        <p>把想法写下来，从第一个页面开始。</p>
      </header>

      <section id="create-app" class="composer-section" aria-label="创建应用">
        <form class="composer" :class="{ 'has-error': promptError }" @submit.prevent="createApp">
          <div class="composer-heading">
            <span class="composer-label"><EditOutlined aria-hidden="true" /> 应用需求</span>
            <span class="composer-note">想法，从这里成形</span>
          </div>
          <div class="prompt-field">
            <textarea
              id="app-prompt"
              ref="promptInput"
              v-model="userPrompt"
              name="app-prompt"
              autocomplete="off"
              :rows="2"
              :maxlength="1000"
              :disabled="creating"
              aria-label="描述你想创建的应用"
              aria-describedby="prompt-help prompt-feedback"
              :aria-invalid="!!promptError"
              @input="resizePromptInput"
              @keydown="handlePromptKeydown"
              @compositionstart="isComposing = true"
              @compositionend="isComposing = false"
            ></textarea>
            <div
              v-if="!userPrompt && !isComposing"
              class="typewriter-placeholder"
              aria-hidden="true"
            >
              <span class="example-prefix">试试：</span>{{ typedText
              }}<span v-if="!paused && !reducedMotion" class="typing-caret"></span>
            </div>
          </div>
          <div class="composer-toolbar">
            <div id="prompt-help" class="composer-tools">
              <template v-if="!userPrompt">
                <button type="button" class="apply-example" @click="applyExample">
                  <kbd>Tab</kbd><span>采用示例</span>
                </button>
                <button
                  v-if="!reducedMotion"
                  type="button"
                  class="motion-toggle"
                  :aria-label="paused ? '继续示例动画' : '暂停示例动画'"
                  :title="paused ? '继续示例动画' : '暂停示例动画'"
                  @click="togglePaused"
                >
                  <CaretRightOutlined v-if="paused" aria-hidden="true" />
                  <PauseOutlined v-else aria-hidden="true" />
                </button>
              </template>
              <span v-else class="input-help">Enter 生成，Shift + Enter 换行</span>
            </div>
            <a-button type="primary" html-type="submit" class="generate-button" :loading="creating">
              {{ creating ? '创建中…' : '生成应用' }}
              <ArrowUpOutlined v-if="!creating" aria-hidden="true" />
            </a-button>
          </div>
        </form>
        <div id="prompt-feedback" class="prompt-feedback" aria-live="polite">
          <span v-if="promptError" class="prompt-error">{{ promptError }}</span>
          <span v-else-if="completionStatus">{{ completionStatus }}</span>
          <span v-else>描述用途、页面内容和喜欢的风格，效果会更贴近你的想法。</span>
        </div>
      </section>

      <section id="deployed-projects" class="projects-section" aria-labelledby="projects-heading">
        <div class="projects-heading">
          <div>
            <h2 id="projects-heading">发现应用</h2>
            <p>这里的每个项目，都已成为可以访问的网站。</p>
          </div>
          <span v-if="!loading && !error" class="project-count">
            <span class="status-dot" aria-hidden="true"></span>
            {{ total }} 个已部署项目
          </span>
        </div>
        <ProjectGallery
          :projects="projects"
          :loading="loading"
          :error="error"
          mode="public"
          empty-title="还没有已部署的项目"
          empty-description="项目部署后会出现在这里。先写下你的想法，创建第一个应用。"
          @retry="reload"
        />
        <div v-if="total > pageSize && !loading && !error" class="pagination-wrapper">
          <a-pagination
            :current="currentPage"
            :page-size="pageSize"
            :total="total"
            :show-size-changer="false"
            @change="changePage"
          />
        </div>
      </section>
    </div>
  </div>
</template>

<script setup lang="ts">
import { onMounted, onUnmounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import {
  ArrowUpOutlined,
  CaretRightOutlined,
  EditOutlined,
  PauseOutlined,
} from '@ant-design/icons-vue'
import { addApp } from '@/api/appController'
import { listDeployedApps } from '@/api/deployedApps'
import ProjectGallery from '@/components/ProjectGallery.vue'
import { useProjectCatalog } from '@/composables/useProjectCatalog'
import { useTypewriterSuggestion } from '@/composables/useTypewriterSuggestion'
import { useLoginUserStore } from '@/stores/loginUser'

const router = useRouter()
const loginUserStore = useLoginUserStore()
const userPrompt = ref('')
const promptInput = ref<HTMLTextAreaElement>()
const creating = ref(false)
const isComposing = ref(false)
const promptError = ref('')
const completionStatus = ref('')

const examples = [
  '做一个个人摄影作品集，用大图展示旅行照片，支持按城市分类。',
  '创建一个读书笔记网站，包含书单、阅读进度和笔记卡片。',
  '设计一个咖啡店官网，展示菜单、门店照片和营业时间。',
  '做一个每周计划应用，支持添加任务、标记完成和按日期查看。',
]
const { currentExample, typedText, paused, reducedMotion, togglePaused } = useTypewriterSuggestion(
  userPrompt,
  examples,
)
const { projects, total, currentPage, pageSize, loading, error, reload, changePage } =
  useProjectCatalog(listDeployedApps)

/** 根据提示词调整输入区高度；达到 CSS 高度上限后内部滚动，删除内容时自动收缩。 */
function resizePromptInput(): void {
  const input = promptInput.value
  if (!input) return
  // 先恢复自动高度，避免删除文字后 scrollHeight 仍受旧高度影响。
  input.style.height = 'auto'
  input.style.height = `${input.scrollHeight}px`
}

// 同时覆盖手动输入、Tab 填入示例，以及窗口宽度变化造成的自动换行。
watch(userPrompt, resizePromptInput, { flush: 'post' })
onMounted(() => {
  resizePromptInput()
  window.addEventListener('resize', resizePromptInput)
})
onUnmounted(() => window.removeEventListener('resize', resizePromptInput))

/** 将当前完整示例填入空输入框并保留焦点，不覆盖用户已经输入的内容。 */
function applyExample(): void {
  if (userPrompt.value || creating.value || isComposing.value) return
  userPrompt.value = currentExample.value
  promptError.value = ''
  completionStatus.value = '已填入示例，可以修改后生成。'
  promptInput.value?.focus()
}

/**
 * 处理空输入的 Tab 补全与 Enter 提交，保留反向切换焦点、换行和输入法行为。
 * @param event 输入框原生键盘事件。
 */
function handlePromptKeydown(event: KeyboardEvent): void {
  if (event.isComposing || isComposing.value || event.keyCode === 229) return
  if (
    event.key === 'Tab' &&
    !event.shiftKey &&
    !event.altKey &&
    !event.ctrlKey &&
    !event.metaKey &&
    !userPrompt.value &&
    currentExample.value
  ) {
    event.preventDefault()
    applyExample()
  } else if (
    event.key === 'Enter' &&
    !event.shiftKey &&
    !event.altKey &&
    !event.ctrlKey &&
    !event.metaKey
  ) {
    event.preventDefault()
    void createApp()
  }
}

/** 校验需求和登录状态后创建应用并打开工作区；失败时保留输入并显示可重试的原因。 */
async function createApp(): Promise<void> {
  if (creating.value) return
  promptError.value = ''
  completionStatus.value = ''
  const prompt = userPrompt.value.trim()
  if (!prompt) {
    promptError.value = '先写下应用需求，或按 Tab 采用一条示例。'
    promptInput.value?.focus()
    return
  }
  if (!loginUserStore.loginUser.id) {
    loginUserStore.openLoginModal()
    return
  }

  creating.value = true
  try {
    const response = await addApp({ initPrompt: prompt })
    if (response.data.code === 0 && response.data.data) {
      await router.push(`/app/chat/${String(response.data.data)}`)
    } else {
      promptError.value = response.data.message || '创建失败，请稍后重试。'
    }
  } catch (cause) {
    console.error('创建应用失败：', cause)
    promptError.value = '暂时无法创建应用，请检查网络后重试。'
  } finally {
    creating.value = false
  }
}
</script>

<style scoped>
#homePage {
  min-height: 100%;
  background: var(--color-page);
  color: var(--color-text);
}

.home-container {
  width: min(calc(100% - 80px), 1120px);
  margin: 0 auto;
  padding: 64px 0;
  container-type: inline-size;
}

.hero-section {
  margin-bottom: 30px;
  text-align: center;
}

.hero-section h1 {
  margin: 0 0 12px;
  color: var(--color-text);
  font-family: var(--font-ui);
  font-size: clamp(28px, 3vw, 36px);
  font-weight: 650;
  letter-spacing: -0.8px;
  line-height: 1.4;
  text-wrap: balance;
}

.hero-section p {
  margin: 0;
  color: var(--color-text-muted);
  font-size: 15px;
}

.composer-section {
  /* 创作区单独限宽，作品列表保留宽版，便于输入时聚焦、浏览时并排比较。 */
  width: min(100%, 760px);
  margin: 0 auto 60px;
  scroll-margin-top: 24px;
}

.composer {
  overflow: hidden;
  padding: 16px 22px 12px;
  border: 1px solid var(--color-border-strong);
  border-radius: 18px;
  background: var(--color-surface);
  box-shadow: var(--shadow-composer);
  transition: border-color 160ms ease;
}

.composer:focus-within {
  border-color: var(--color-focus);
}

.composer.has-error {
  border-color: var(--color-error);
}

.composer-heading {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
  margin-bottom: 10px;
}

.composer-label {
  display: flex;
  align-items: center;
  gap: 8px;
  color: var(--color-primary);
  font-size: 13px;
  font-weight: 600;
}

.composer-label :deep(.anticon) {
  display: inline-flex;
  width: 23px;
  height: 23px;
  align-items: center;
  justify-content: center;
  border-radius: 7px;
  background: var(--color-primary-soft);
  color: var(--color-primary);
  font-size: 14px;
}

.composer-note {
  color: var(--color-text-muted);
  font-size: 12px;
}

.prompt-field {
  position: relative;
}

.prompt-field textarea {
  display: block;
  width: 100%;
  min-height: 65px;
  max-height: min(240px, 40vh);
  padding: 4px 0;
  border: 0;
  border-radius: 3px;
  outline: none;
  background: transparent;
  color: var(--color-text);
  font: inherit;
  font-size: 16px;
  line-height: 1.8;
  overflow-y: auto;
  overscroll-behavior-y: contain;
  scrollbar-width: thin;
  scrollbar-color: var(--color-border-strong) transparent;
  resize: none;
}

.typewriter-placeholder {
  position: absolute;
  top: 4px;
  left: 0;
  right: 5px;
  max-height: 58px;
  overflow: hidden;
  color: var(--color-text-secondary);
  font-size: 16px;
  line-height: 1.8;
  overflow-wrap: anywhere;
  pointer-events: none;
}

.example-prefix {
  color: var(--color-text-muted);
}

.typing-caret {
  display: inline-block;
  width: 2px;
  height: 1em;
  margin-left: 3px;
  background: var(--color-primary);
  vertical-align: -2px;
}

.composer-toolbar,
.composer-tools {
  display: flex;
  align-items: center;
}

.composer-toolbar {
  justify-content: space-between;
  gap: 12px;
  margin-top: 8px;
  padding-top: 10px;
  border-top: 1px solid var(--color-border);
}

.composer-tools {
  min-height: 34px;
  gap: 8px;
}

.apply-example,
.motion-toggle {
  display: inline-flex;
  min-height: 32px;
  align-items: center;
  justify-content: center;
  gap: 7px;
  padding: 0 7px;
  border: 0;
  border-radius: 5px;
  background: transparent;
  color: var(--color-text-muted);
  cursor: pointer;
  font: inherit;
  font-size: 12px;
  touch-action: manipulation;
}

.apply-example:hover,
.motion-toggle:hover {
  background: var(--color-primary-soft);
  color: var(--color-primary);
}

.apply-example kbd {
  padding: 1px 5px;
  border: 1px solid var(--color-primary-border);
  border-bottom-width: 2px;
  border-radius: 4px;
  background: var(--color-surface);
  font: inherit;
  font-size: 11px;
  line-height: 19px;
}

.motion-toggle {
  width: 30px;
  padding: 0;
}

.apply-example:focus-visible,
.motion-toggle:focus-visible {
  outline: 3px solid var(--color-focus);
  outline-offset: 2px;
}

.input-help {
  color: var(--color-text-muted);
  font-size: 12px;
}

.generate-button {
  display: inline-flex;
  height: 38px;
  align-items: center;
  gap: 10px;
  padding: 0 16px;
  border-color: var(--color-primary);
  border-radius: 9px;
  background: var(--color-primary);
  box-shadow: none;
  font-size: 13px;
  font-weight: 600;
}

.generate-button:hover {
  border-color: var(--color-primary-hover);
  background: var(--color-primary-hover);
}

.generate-button :deep(.anticon) {
  font-size: 16px;
}

.prompt-feedback {
  min-height: 28px;
  padding: 12px 2px 0;
  color: var(--color-text-muted);
  font-size: 12px;
  line-height: 1.6;
  text-align: center;
}

.prompt-error {
  color: var(--color-error);
}

.projects-heading {
  display: flex;
  align-items: end;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 24px;
  padding-top: 26px;
  border-top: 1px solid var(--color-border);
}

.projects-heading h2 {
  margin: 0 0 7px;
  color: var(--color-text);
  font-size: 22px;
  font-weight: 650;
}

.projects-heading p {
  margin: 0;
  color: var(--color-text-muted);
  font-size: 13px;
  line-height: 1.6;
}

.project-count {
  display: inline-flex;
  flex: 0 0 auto;
  align-items: center;
  gap: 7px;
  padding-bottom: 2px;
  color: var(--color-text-muted);
  font-size: 12px;
  font-variant-numeric: tabular-nums;
}

.status-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--color-primary);
}

.pagination-wrapper {
  display: flex;
  justify-content: center;
  margin-top: 30px;
}

@media (max-width: 1100px) {
  .home-container {
    width: calc(100% - 48px);
    padding-top: 40px;
  }
}

@media (max-width: 600px) {
  .home-container {
    width: calc(100% - 32px);
    padding: 32px 0 40px;
  }

  .hero-section {
    margin-bottom: 22px;
  }

  .hero-section h1 {
    font-size: 27px;
  }

  .hero-section p {
    font-size: 13px;
  }

  .composer-section {
    margin-bottom: 32px;
  }

  .composer {
    padding: 13px 14px;
  }

  .composer-note {
    display: none;
  }

  .prompt-field textarea,
  .typewriter-placeholder {
    font-size: 16px;
  }

  .input-help {
    font-size: 10px;
  }

  .apply-example {
    padding-left: 0;
  }

  .generate-button {
    gap: 4px;
    padding: 0 10px;
  }

  .projects-heading {
    align-items: start;
    flex-direction: column;
    gap: 12px;
    margin-bottom: 18px;
  }
}

@media (prefers-reduced-motion: reduce) {
  .composer {
    transition: none;
  }
}
</style>
