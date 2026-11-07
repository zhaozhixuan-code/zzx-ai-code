<template>
  <div id="appChatPage">
    <!-- 顶部栏 -->
    <div class="header-bar">
      <div class="header-left">
        <h1 class="app-name">{{ appInfo?.appName || '网站生成器' }}</h1>
        <a-tag v-if="appInfo?.codeGenType" color="blue" class="code-gen-type-tag">
          {{ formatCodeGenType(appInfo.codeGenType) }}
        </a-tag>
      </div>
      <div class="header-right">
        <a-button type="default" @click="showAppDetail">
          <template #icon>
            <InfoCircleOutlined />
          </template>
          应用详情
        </a-button>
        <a-button
          type="primary"
          ghost
          @click="downloadCode"
          :loading="downloading"
          :disabled="!isOwner"
        >
          <template #icon>
            <DownloadOutlined />
          </template>
          下载代码
        </a-button>
        <a-button type="primary" @click="deployApp" :loading="deploying">
          <template #icon>
            <CloudUploadOutlined />
          </template>
          部署
        </a-button>
      </div>
    </div>

    <!-- 主要内容区域 -->
    <div class="main-content">
      <!-- 对话区域在桌面布局中位于右侧 -->
      <div class="chat-section">
        <div class="chat-header">
          <div class="chat-heading">
            <MessageOutlined aria-hidden="true" />
            <h2>生成对话</h2>
          </div>
          <span v-if="isGenerating" class="chat-status">正在生成</span>
          <span v-else class="chat-hint">描述想调整的内容</span>
        </div>
        <!-- 消息区域 -->
        <div class="messages-container" ref="messagesContainer">
          <div v-if="messages.length === 0" class="conversation-empty">
            <p>从一个具体需求开始，逐步完善页面。</p>
          </div>
          <!-- 加载更多按钮 -->
          <div v-if="hasMoreHistory" class="load-more-container">
            <a-button type="link" @click="loadMoreHistory" :loading="loadingHistory" size="small">
              加载更多历史消息
            </a-button>
          </div>
          <div v-for="(message, index) in messages" :key="index" class="message-item">
            <div v-if="message.type === 'user'" class="user-message">
              <div class="message-content">{{ message.content }}</div>
              <div class="message-avatar">
                <a-avatar :src="loginUserStore.loginUser.userAvatar" />
              </div>
            </div>
            <div v-else class="ai-message">
              <div class="message-avatar">
                <a-avatar :src="aiAvatar" />
              </div>
              <div class="message-content">
                <MarkdownRenderer v-if="message.content" :content="message.content" />
                <div v-if="message.loading" class="loading-indicator">
                  <a-spin size="small" />
                  <span>AI 正在思考...</span>
                </div>
              </div>
            </div>
          </div>
        </div>

        <!-- 选中元素信息展示 -->
        <a-alert
          v-if="selectedElementInfo"
          class="selected-element-alert"
          type="info"
          closable
          @close="clearSelectedElement"
        >
          <template #message>
            <div class="selected-element-info">
              <div class="element-header">
                <span class="element-tag">
                  选中元素：{{ selectedElementInfo.tagName.toLowerCase() }}
                </span>
                <span v-if="selectedElementInfo.id" class="element-id">
                  #{{ selectedElementInfo.id }}
                </span>
                <span v-if="selectedElementInfo.className" class="element-class">
                  .{{ selectedElementInfo.className.split(' ').join('.') }}
                </span>
              </div>
              <div class="element-details">
                <div v-if="selectedElementInfo.textContent" class="element-item">
                  内容: {{ selectedElementInfo.textContent.substring(0, 50) }}
                  {{ selectedElementInfo.textContent.length > 50 ? '...' : '' }}
                </div>
                <div v-if="selectedElementInfo.pagePath" class="element-item">
                  页面路径: {{ selectedElementInfo.pagePath }}
                </div>
                <div class="element-item">
                  选择器:
                  <code class="element-selector-code">{{ selectedElementInfo.selector }}</code>
                </div>
              </div>
            </div>
          </template>
        </a-alert>

        <!-- 用户消息输入框 -->
        <div class="input-container">
          <div class="input-wrapper">
            <a-tooltip v-if="!isOwner" title="无法在别人的作品下对话哦~" placement="top">
              <a-textarea
                v-model:value="userInput"
                :placeholder="getInputPlaceholder()"
                :rows="4"
                :maxlength="1000"
                aria-label="描述希望生成或修改的内容"
                @keydown.enter.prevent="sendMessage"
                :disabled="isGenerating || !isOwner"
              />
            </a-tooltip>
            <a-textarea
              v-else
              v-model:value="userInput"
              :placeholder="getInputPlaceholder()"
              :rows="4"
              :maxlength="1000"
              aria-label="描述希望生成或修改的内容"
              @keydown.enter.prevent="sendMessage"
              :disabled="isGenerating"
            />
            <div class="input-actions">
              <a-button
                type="primary"
                @click="sendMessage"
                :loading="isGenerating"
                :disabled="!isOwner"
                aria-label="发送消息"
              >
                <template #icon>
                  <SendOutlined aria-hidden="true" />
                </template>
              </a-button>
            </div>
          </div>
        </div>
      </div>
      <!-- 预览区域在桌面布局中位于左侧 -->
      <div class="preview-section">
        <div class="preview-header">
          <h2>页面预览</h2>
          <div class="preview-actions">
            <a-button
              v-if="isOwner && previewUrl"
              type="link"
              :danger="isEditMode"
              @click="toggleEditMode"
              :class="{ 'edit-mode-active': isEditMode }"
              style="padding: 0; height: auto; margin-right: 12px"
            >
              <template #icon>
                <EditOutlined aria-hidden="true" />
              </template>
              {{ isEditMode ? '退出编辑' : '编辑模式' }}
            </a-button>
            <a-button v-if="previewUrl" type="link" @click="openInNewTab">
              <template #icon>
                <ExportOutlined aria-hidden="true" />
              </template>
              新窗口打开
            </a-button>
          </div>
        </div>
        <div class="preview-content">
          <div v-if="!previewUrl && !isGenerating" class="preview-placeholder">
            <DesktopOutlined class="placeholder-icon" aria-hidden="true" />
            <p>生成完成后，页面会显示在这里</p>
          </div>
          <div v-else-if="isGenerating" class="preview-loading">
            <a-spin size="large" />
            <p>正在生成网站...</p>
          </div>
          <iframe
            v-else
            :src="previewUrl"
            class="preview-iframe"
            title="生成的网站预览"
            frameborder="0"
            @load="onIframeLoad"
          ></iframe>
        </div>
      </div>
    </div>

    <!-- 应用详情弹窗 -->
    <AppDetailModal
      v-model:open="appDetailVisible"
      :app="appInfo"
      :show-actions="isOwner || isAdmin"
      @edit="editApp"
      @delete="deleteApp"
    />

    <!-- 部署成功弹窗 -->
    <DeploySuccessModal
      v-model:open="deployModalVisible"
      :deploy-url="deployUrl"
      @open-site="openDeployedSite"
    />
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted, nextTick, onUnmounted, computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { message } from 'ant-design-vue'
import { useLoginUserStore } from '@/stores/loginUser'
import {
  getAppVoById,
  deployApp as deployAppApi,
  deleteApp as deleteAppApi,
} from '@/api/appController'
import { listAppChatHistory } from '@/api/chatHistoryController'
import { CodeGenTypeEnum, formatCodeGenType } from '@/utils/codeGenTypes'
import request from '@/request'

import MarkdownRenderer from '@/components/MarkdownRenderer.vue'
import AppDetailModal from '@/components/AppDetailModal.vue'
import DeploySuccessModal from '@/components/DeploySuccessModal.vue'
import aiAvatar from '@/assets/aiAvatar.png'
import { API_BASE_URL, getStaticPreviewUrl } from '@/config/env'
import { VisualEditor, type ElementInfo } from '@/utils/visualEditor'

import {
  CloudUploadOutlined,
  SendOutlined,
  ExportOutlined,
  InfoCircleOutlined,
  DownloadOutlined,
  EditOutlined,
  DesktopOutlined,
  MessageOutlined,
} from '@ant-design/icons-vue'

const route = useRoute()
const router = useRouter()
const loginUserStore = useLoginUserStore()

// 应用信息
const appInfo = ref<API.AppVO>()
const appId = ref<any>()

// 对话相关
interface Message {
  type: 'user' | 'ai'
  content: string
  loading?: boolean
  createTime?: string
}

const messages = ref<Message[]>([])
const userInput = ref('')
const isGenerating = ref(false)
const messagesContainer = ref<HTMLElement>()

// 对话历史相关
const loadingHistory = ref(false)
const hasMoreHistory = ref(false)
const lastCreateTime = ref<string>()
const historyLoaded = ref(false)

// 预览相关
const previewUrl = ref('')
const previewReady = ref(false)

// 部署相关
const deploying = ref(false)
const deployModalVisible = ref(false)
const deployUrl = ref('')

// 下载相关
const downloading = ref(false)

// 可视化编辑相关
const isEditMode = ref(false)
const selectedElementInfo = ref<ElementInfo | null>(null)
const visualEditor = new VisualEditor({
  onElementSelected: (elementInfo: ElementInfo) => {
    selectedElementInfo.value = elementInfo
  },
})

// 权限相关
const isOwner = computed(() => {
  return appInfo.value?.userId === loginUserStore.loginUser.id
})

const isAdmin = computed(() => {
  return loginUserStore.loginUser.userRole === 'admin'
})

// 应用详情相关
const appDetailVisible = ref(false)

// 显示应用详情
const showAppDetail = () => {
  appDetailVisible.value = true
}

// 加载对话历史
const loadChatHistory = async (isLoadMore = false) => {
  if (!appId.value || loadingHistory.value) return
  loadingHistory.value = true
  try {
    const params: API.listAppChatHistoryParams = {
      appId: appId.value,
      pageSize: 10,
    }
    // 如果是加载更多，传递最后一条消息的创建时间作为游标
    if (isLoadMore && lastCreateTime.value) {
      params.lastCreateTime = lastCreateTime.value
    }
    const res = await listAppChatHistory(params)
    if (res.data.code === 0 && res.data.data) {
      const chatHistories = res.data.data.records || []
      if (chatHistories.length > 0) {
        // 将对话历史转换为消息格式，并按时间正序排列（老消息在前）
        const historyMessages: Message[] = chatHistories
          .map((chat) => ({
            type: (chat.messageType === 'user' ? 'user' : 'ai') as 'user' | 'ai',
            content: chat.message || '',
            createTime: chat.createTime,
          }))
          .reverse() // 反转数组，让老消息在前
        if (isLoadMore) {
          // 加载更多时，将历史消息添加到开头
          messages.value.unshift(...historyMessages)
        } else {
          // 初始加载，直接设置消息列表
          messages.value = historyMessages
        }
        // 更新游标
        lastCreateTime.value = chatHistories[chatHistories.length - 1]?.createTime
        // 检查是否还有更多历史
        hasMoreHistory.value = chatHistories.length === 10
      } else {
        hasMoreHistory.value = false
      }
      historyLoaded.value = true
    }
  } catch (error) {
    console.error('加载对话历史失败：', error)
    message.error('加载对话历史失败')
  } finally {
    loadingHistory.value = false
  }
}

// 加载更多历史消息
const loadMoreHistory = async () => {
  await loadChatHistory(true)
}

// 获取应用信息
const fetchAppInfo = async () => {
  const id = route.params.id as string
  if (!id) {
    message.error('应用ID不存在')
    router.push('/')
    return
  }

  appId.value = id

  try {
    const res = await getAppVoById({ id: id as unknown as number })
    if (res.data.code === 0 && res.data.data) {
      appInfo.value = res.data.data

      // 先加载对话历史
      await loadChatHistory()
      // 如果有至少2条对话记录，展示对应的网站
      if (messages.value.length >= 2) {
        updatePreview()
      }
      // 检查是否需要自动发送初始提示词
      // 只有在是自己的应用且没有对话历史时才自动发送
      if (
        appInfo.value.initPrompt &&
        isOwner.value &&
        messages.value.length === 0 &&
        historyLoaded.value
      ) {
        await sendInitialMessage(appInfo.value.initPrompt)
      }
    } else {
      message.error('获取应用信息失败')
      router.push('/')
    }
  } catch (error) {
    console.error('获取应用信息失败：', error)
    message.error('获取应用信息失败')
    router.push('/')
  }
}

// 发送初始消息
const sendInitialMessage = async (prompt: string) => {
  // 添加用户消息
  messages.value.push({
    type: 'user',
    content: prompt,
  })

  // 添加AI消息占位符
  const aiMessageIndex = messages.value.length
  messages.value.push({
    type: 'ai',
    content: '',
    loading: true,
  })

  await nextTick()
  scrollToBottom()

  // 开始生成
  isGenerating.value = true
  await generateCode(prompt, aiMessageIndex)
}

// 发送消息
const sendMessage = async () => {
  if (!userInput.value.trim() || isGenerating.value) {
    return
  }

  let message = userInput.value.trim()
  // 如果有选中的元素，将元素信息添加到提示词中
  if (selectedElementInfo.value) {
    let elementContext = `\n\n选中元素信息：`
    if (selectedElementInfo.value.pagePath) {
      elementContext += `\n- 页面路径: ${selectedElementInfo.value.pagePath}`
    }
    elementContext += `\n- 标签: ${selectedElementInfo.value.tagName.toLowerCase()}\n- 选择器: ${selectedElementInfo.value.selector}`
    if (selectedElementInfo.value.textContent) {
      elementContext += `\n- 当前内容: ${selectedElementInfo.value.textContent.substring(0, 100)}`
    }
    message += elementContext
  }
  userInput.value = ''
  // 添加用户消息（包含元素信息）
  messages.value.push({
    type: 'user',
    content: message,
  })

  // 发送消息后，清除选中元素并退出编辑模式
  if (selectedElementInfo.value) {
    clearSelectedElement()
    if (isEditMode.value) {
      toggleEditMode()
    }
  }

  // 添加AI消息占位符
  const aiMessageIndex = messages.value.length
  messages.value.push({
    type: 'ai',
    content: '',
    loading: true,
  })

  await nextTick()
  scrollToBottom()

  // 开始生成
  isGenerating.value = true
  await generateCode(message, aiMessageIndex)
}

// 生成代码 - 使用 EventSource 处理流式响应
const generateCode = async (userMessage: string, aiMessageIndex: number) => {
  let eventSource: EventSource | null = null
  let streamCompleted = false

  try {
    // 获取 axios 配置的 baseURL
    const baseURL = request.defaults.baseURL || API_BASE_URL

    // 构建URL参数
    const params = new URLSearchParams({
      appId: appId.value || '',
      message: userMessage,
    })

    const url = `${baseURL}/app/chat/gen/code?${params}`

    // 创建 EventSource 连接
    eventSource = new EventSource(url, {
      withCredentials: true,
    })

    let fullContent = ''

    // 处理接收到的消息
    eventSource.onmessage = function (event) {
      if (streamCompleted) return

      try {
        // 解析JSON包装的数据
        const parsed = JSON.parse(event.data)
        const content = parsed.d

        // 拼接内容
        if (content !== undefined && content !== null) {
          fullContent += content
          messages.value[aiMessageIndex].content = fullContent
          messages.value[aiMessageIndex].loading = false
          scrollToBottom()
        }
      } catch (error) {
        console.error('解析消息失败:', error)
        handleError(error, aiMessageIndex)
      }
    }

    // 处理done事件
    eventSource.addEventListener('done', function () {
      if (streamCompleted) return

      streamCompleted = true
      isGenerating.value = false
      eventSource?.close()

      // 延迟更新预览，确保后端已完成处理
      setTimeout(async () => {
        await fetchAppInfo()
        updatePreview()
      }, 1000)
    })

    // 处理business-error事件（后端限流等错误）
    eventSource.addEventListener('business-error', function (event: MessageEvent) {
      if (streamCompleted) return

      try {
        const errorData = JSON.parse(event.data)
        console.error('SSE业务错误事件:', errorData)

        // 显示具体的错误信息
        const errorMessage = errorData.message || '生成过程中出现错误'
        messages.value[aiMessageIndex].content = `❌ ${errorMessage}`
        messages.value[aiMessageIndex].loading = false
        message.error(errorMessage)

        streamCompleted = true
        isGenerating.value = false
        eventSource?.close()
      } catch (parseError) {
        console.error('解析错误事件失败:', parseError, '原始数据:', event.data)
        handleError(new Error('服务器返回错误'), aiMessageIndex)
      }
    })

    // 处理错误
    eventSource.onerror = function () {
      if (streamCompleted || !isGenerating.value) return
      // 检查是否是正常的连接关闭
      if (eventSource?.readyState === EventSource.CONNECTING) {
        streamCompleted = true
        isGenerating.value = false
        eventSource?.close()

        setTimeout(async () => {
          await fetchAppInfo()
          updatePreview()
        }, 1000)
      } else {
        handleError(new Error('SSE连接错误'), aiMessageIndex)
      }
    }
  } catch (error) {
    console.error('创建 EventSource 失败：', error)
    handleError(error, aiMessageIndex)
  }
}

// 错误处理函数
const handleError = (error: unknown, aiMessageIndex: number) => {
  console.error('生成代码失败：', error)
  messages.value[aiMessageIndex].content = '抱歉，生成过程中出现了错误，请重试。'
  messages.value[aiMessageIndex].loading = false
  message.error('生成失败，请重试')
  isGenerating.value = false
}

// 更新预览
const updatePreview = () => {
  if (appId.value) {
    const codeGenType = appInfo.value?.codeGenType || CodeGenTypeEnum.HTML
    const newPreviewUrl = getStaticPreviewUrl(codeGenType, appId.value)
    previewUrl.value = newPreviewUrl
    previewReady.value = true
  }
}

// 滚动到底部
const scrollToBottom = () => {
  if (messagesContainer.value) {
    messagesContainer.value.scrollTop = messagesContainer.value.scrollHeight
  }
}

// 下载代码
const downloadCode = async () => {
  if (!appId.value) {
    message.error('应用ID不存在')
    return
  }
  downloading.value = true
  try {
    const API_BASE_URL = request.defaults.baseURL || ''
    const url = `${API_BASE_URL}/app/download/${appId.value}`
    const response = await fetch(url, {
      method: 'GET',
      credentials: 'include',
    })
    if (!response.ok) {
      throw new Error(`下载失败: ${response.status}`)
    }
    // 获取文件名
    const contentDisposition = response.headers.get('Content-Disposition')
    const fileName = contentDisposition?.match(/filename="(.+)"/)?.[1] || `app-${appId.value}.zip`
    // 下载文件
    const blob = await response.blob()
    const downloadUrl = URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = downloadUrl
    link.download = fileName
    link.click()
    // 清理
    URL.revokeObjectURL(downloadUrl)
    message.success('代码下载成功')
  } catch (error) {
    console.error('下载失败：', error)
    message.error('下载失败，请重试')
  } finally {
    downloading.value = false
  }
}

// 部署应用
const deployApp = async () => {
  if (!appId.value) {
    message.error('应用ID不存在')
    return
  }

  deploying.value = true
  try {
    const res = await deployAppApi({
      appId: appId.value as unknown as number,
    })

    if (res.data.code === 0 && res.data.data) {
      deployUrl.value = res.data.data
      deployModalVisible.value = true
      message.success('部署成功')
    } else {
      message.error('部署失败：' + res.data.message)
    }
  } catch (error) {
    console.error('部署失败：', error)
    message.error('部署失败，请重试')
  } finally {
    deploying.value = false
  }
}

// 在新窗口打开预览
const openInNewTab = () => {
  if (previewUrl.value) {
    window.open(previewUrl.value, '_blank')
  }
}

// 打开部署的网站
const openDeployedSite = () => {
  if (deployUrl.value) {
    window.open(deployUrl.value, '_blank')
  }
}

// iframe加载完成
const onIframeLoad = () => {
  previewReady.value = true
  const iframe = document.querySelector('.preview-iframe') as HTMLIFrameElement
  if (iframe) {
    visualEditor.init(iframe)
    visualEditor.onIframeLoad()
  }
}

// 编辑应用
const editApp = () => {
  if (appInfo.value?.id) {
    router.push(`/app/edit/${appInfo.value.id}`)
  }
}

// 删除应用
const deleteApp = async () => {
  if (!appInfo.value?.id) return

  try {
    const res = await deleteAppApi({ id: appInfo.value.id })
    if (res.data.code === 0) {
      message.success('删除成功')
      appDetailVisible.value = false
      router.push('/')
    } else {
      message.error('删除失败：' + res.data.message)
    }
  } catch (error) {
    console.error('删除失败：', error)
    message.error('删除失败')
  }
}

// 可视化编辑相关函数
const toggleEditMode = () => {
  // 检查 iframe 是否已经加载
  const iframe = document.querySelector('.preview-iframe') as HTMLIFrameElement
  if (!iframe) {
    message.warning('请等待页面加载完成')
    return
  }
  // 确保 visualEditor 已初始化
  if (!previewReady.value) {
    message.warning('请等待页面加载完成')
    return
  }
  const newEditMode = visualEditor.toggleEditMode()
  isEditMode.value = newEditMode
}

const clearSelectedElement = () => {
  selectedElementInfo.value = null
  visualEditor.clearSelection()
}

const getInputPlaceholder = () => {
  if (selectedElementInfo.value) {
    return `正在编辑 ${selectedElementInfo.value.tagName.toLowerCase()} 元素，描述您想要的修改...`
  }
  return '请描述你想生成的网站，越详细效果越好哦'
}

// 页面加载时获取应用信息
onMounted(() => {
  fetchAppInfo()

  // 监听 iframe 消息
  window.addEventListener('message', (event) => {
    visualEditor.handleIframeMessage(event)
  })
})

// 清理资源
onUnmounted(() => {
  // EventSource 会在组件卸载时自动清理
})
</script>

<style scoped>
#appChatPage {
  display: flex;
  height: 100vh;
  min-height: 0;
  flex-direction: column;
  padding: 0 14px 14px;
  background: #f3f5f7;
  color: #1d2935;
}

.header-bar {
  display: flex;
  min-height: 62px;
  flex: 0 0 62px;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 0 8px;
  border-bottom: 1px solid #e0e5e9;
}

.header-left,
.header-right {
  display: flex;
  min-width: 0;
  align-items: center;
  gap: 10px;
}

.app-name {
  overflow: hidden;
  margin: 0;
  color: #1d2935;
  font-size: 17px;
  font-weight: 650;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.code-gen-type-tag {
  flex: 0 0 auto;
  border-color: #d8e1ee;
  border-radius: 4px;
  background: #edf2f8;
  color: #425d85;
  font-size: 12px;
}

.header-right :deep(.ant-btn) {
  height: 34px;
  border-radius: 5px;
  font-size: 13px;
}

.main-content {
  display: flex;
  min-height: 0;
  flex: 1;
  gap: 12px;
  padding-top: 12px;
  overflow: hidden;
}

.preview-section,
.chat-section {
  display: flex;
  min-height: 0;
  flex-direction: column;
  overflow: hidden;
  border: 1px solid #dfe4e8;
  border-radius: 7px;
  background: #fff;
}

.preview-section {
  order: 1;
  min-width: 0;
  flex: 1 1 auto;
}

.chat-section {
  order: 2;
  min-width: 340px;
  max-width: 520px;
  flex: 0 0 38%;
}

.preview-header,
.chat-header {
  display: flex;
  min-height: 52px;
  flex: 0 0 52px;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 0 15px;
  border-bottom: 1px solid #e5e9ec;
}

.preview-header h2,
.chat-heading h2 {
  margin: 0;
  color: #293744;
  font-size: 14px;
  font-weight: 650;
}

.preview-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}

.preview-actions :deep(.ant-btn) {
  height: 30px;
  padding: 0 7px;
  color: #435f91;
  font-size: 12px;
}

.chat-heading {
  display: flex;
  align-items: center;
  gap: 8px;
  color: #5572a8;
}

.chat-heading :deep(.anticon) {
  font-size: 15px;
}

.chat-status,
.chat-hint {
  color: #7c8994;
  font-size: 12px;
}

.chat-status {
  color: #2858c7;
}

.messages-container {
  min-height: 0;
  flex: 1;
  padding: 16px 14px;
  overflow-y: auto;
  overscroll-behavior: contain;
  scroll-behavior: smooth;
}

.message-item {
  margin-bottom: 12px;
}

.user-message,
.ai-message {
  display: flex;
  align-items: flex-start;
  gap: 8px;
}

.user-message {
  justify-content: flex-end;
}

.ai-message {
  justify-content: flex-start;
}

.message-content {
  max-width: 86%;
  min-width: 0;
  padding: 10px 12px;
  border: 1px solid #e2e7eb;
  border-radius: 7px;
  color: #33414d;
  line-height: 1.6;
  overflow-wrap: anywhere;
}

.user-message .message-content {
  border-color: #2858c7;
  background: #2858c7;
  color: #fff;
}

.ai-message .message-content {
  background: #f5f7f8;
}

.message-avatar {
  flex: 0 0 auto;
}

.loading-indicator {
  display: flex;
  align-items: center;
  gap: 8px;
  color: #667480;
}

.conversation-empty {
  display: grid;
  min-height: 100%;
  place-items: center;
  padding: 20px;
  color: #7b8791;
  text-align: center;
}

.conversation-empty p {
  max-width: 220px;
  margin: 0;
  font-size: 13px;
  line-height: 1.7;
}

.load-more-container {
  margin-bottom: 14px;
  text-align: center;
}

.input-container {
  flex: 0 0 auto;
  padding: 12px;
  border-top: 1px solid #e5e9ec;
  background: #fff;
}

.input-wrapper {
  position: relative;
}

.input-wrapper :deep(.ant-input) {
  min-height: 96px;
  padding: 10px 46px 34px 11px;
  border-color: #dce3e8;
  border-radius: 6px;
  font-size: 13px;
  line-height: 1.6;
  resize: vertical;
}

.input-wrapper :deep(.ant-input:focus),
.input-wrapper :deep(.ant-input-focused) {
  border-color: #7592cf;
  box-shadow: 0 0 0 2px rgba(40, 88, 199, 0.1);
}

.input-actions {
  position: absolute;
  right: 8px;
  bottom: 8px;
}

.input-actions :deep(.ant-btn) {
  width: 32px;
  height: 32px;
  border-radius: 5px;
  background: #2858c7;
}

.preview-content {
  position: relative;
  min-height: 0;
  flex: 1;
  overflow: hidden;
  background: #f0f2f4;
}

.preview-placeholder,
.preview-loading {
  display: flex;
  height: 100%;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 12px;
  padding: 20px;
  color: #73808b;
  text-align: center;
}

.preview-placeholder p,
.preview-loading p {
  margin: 0;
  font-size: 13px;
}

.placeholder-icon {
  color: #6882b7;
  font-size: 30px;
}

.preview-iframe {
  display: block;
  width: 100%;
  height: 100%;
  border: 0;
  background: #fff;
}

.selected-element-alert {
  margin: 0 12px 10px;
  border-radius: 6px;
}

.selected-element-info {
  max-width: 100%;
  line-height: 1.5;
}

.element-header {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
  margin-bottom: 7px;
}

.element-tag {
  color: #2858c7;
  font-weight: 600;
}

.element-id {
  color: #42785c;
}

.element-class {
  color: #a45e42;
}

.element-details {
  display: grid;
  gap: 5px;
  color: #586671;
}

.element-item {
  overflow-wrap: anywhere;
  font-size: 12px;
}

.element-selector-code {
  padding: 2px 5px;
  border: 1px solid #dce3e8;
  border-radius: 3px;
  background: #f3f5f6;
  color: #76524a;
  font-size: 11px;
  overflow-wrap: anywhere;
}

.edit-mode-active {
  color: #35684b !important;
}

@media (max-width: 1024px) {
  .main-content {
    flex-direction: column;
    gap: 10px;
  }

  .preview-section,
  .chat-section {
    min-height: 0;
    max-width: none;
    flex: 1 1 50%;
  }

  .chat-section {
    min-width: 0;
  }
}

@media (max-width: 760px) {
  #appChatPage {
    height: calc(100vh - 150px);
    min-height: 520px;
    padding: 0 8px 8px;
  }

  .header-bar {
    min-height: 58px;
    flex-basis: auto;
    flex-wrap: wrap;
    gap: 8px;
    padding: 8px 2px;
  }

  .header-left {
    flex: 1 1 100%;
  }

  .header-right {
    width: 100%;
    justify-content: flex-end;
    gap: 6px;
  }

  .header-right :deep(.ant-btn) {
    height: 30px;
    padding: 0 7px;
    font-size: 12px;
  }

  .main-content {
    padding-top: 8px;
  }

  .preview-section,
  .chat-section {
    flex-basis: 50%;
  }

  .preview-header,
  .chat-header {
    min-height: 46px;
    flex-basis: 46px;
    padding: 0 10px;
  }

  .preview-actions {
    gap: 2px;
  }

  .preview-actions :deep(.ant-btn) {
    padding: 0 4px;
    font-size: 11px;
  }

  .messages-container {
    padding: 12px 9px;
  }

  .message-content {
    max-width: 90%;
    padding: 9px 10px;
  }

  .input-container {
    padding: 9px;
  }
}

@media (prefers-reduced-motion: reduce) {
  .messages-container {
    scroll-behavior: auto;
  }
}
</style>
