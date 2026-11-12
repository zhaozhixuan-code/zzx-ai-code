<template>
  <article class="app-card">
    <component
      :is="mode === 'public' && deployedUrl ? 'a' : RouterLink"
      v-bind="previewLink"
      class="app-preview"
      :aria-label="
        mode === 'public' ? `访问${app.appName || '应用'}网站` : `编辑${app.appName || '应用'}`
      "
    >
      <img
        v-if="app.cover && !coverFailed"
        :src="app.cover"
        :alt="`${app.appName || '应用'}页面预览`"
        width="640"
        height="400"
        loading="lazy"
        @error="coverFailed = true"
      />
      <div v-else class="app-placeholder" aria-hidden="true">
        <div class="mini-browser">
          <div class="mini-browser-bar"><i></i><i></i><i></i></div>
          <div class="mini-browser-body">
            <DesktopOutlined />
            <span>{{ app.appName || '新应用' }}</span>
            <div class="mini-browser-lines"><i></i><i></i></div>
          </div>
        </div>
      </div>
      <span class="project-status" :class="{ 'is-draft': !deployedUrl }">
        <span aria-hidden="true"></span>{{ deployedUrl ? '已部署' : '草稿' }}
      </span>
    </component>

    <div class="app-info">
      <h3 class="app-title">
        <component :is="mode === 'public' && deployedUrl ? 'a' : RouterLink" v-bind="previewLink">
          {{ app.appName || '未命名应用' }}
        </component>
      </h3>
      <div class="app-meta">
        <span class="app-author">
          <a-avatar :src="app.user?.userAvatar" :size="20" aria-hidden="true">
            {{ app.user?.userName?.charAt(0) || 'U' }}
          </a-avatar>
          <span>{{ app.user?.userName || '创作者' }}</span>
        </span>
        <time v-if="displayDate" :datetime="projectDate">{{ displayDate }}</time>
      </div>
      <div class="app-actions">
        <RouterLink v-if="mode === 'personal'" :to="workspaceUrl" class="edit-link">
          继续编辑<EditOutlined aria-hidden="true" />
        </RouterLink>
        <span v-else class="public-caption">打开应用，看看它的样子</span>
        <a v-if="deployedUrl" :href="deployedUrl" target="_blank" rel="noopener noreferrer">
          访问网站<ExportOutlined aria-hidden="true" />
        </a>
      </div>
    </div>
  </article>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { RouterLink } from 'vue-router'
import { DesktopOutlined, EditOutlined, ExportOutlined } from '@ant-design/icons-vue'
import { getDeployUrl } from '@/config/env'

const props = withDefaults(defineProps<{ app: API.AppVO; mode?: 'public' | 'personal' }>(), {
  mode: 'personal',
})
const coverFailed = ref(false)
const deployedUrl = computed(() =>
  props.app.deployKey?.trim() ? getDeployUrl(props.app.deployKey) : '',
)
const workspaceUrl = computed(() => `/app/chat/${props.app.id}`)
const previewLink = computed(() =>
  props.mode === 'public' && deployedUrl.value
    ? { href: deployedUrl.value, target: '_blank', rel: 'noopener noreferrer' }
    : { to: workspaceUrl.value },
)
const projectDate = computed(() =>
  props.mode === 'public' ? props.app.deployedTime : props.app.updateTime || props.app.createTime,
)
const displayDate = computed(() => {
  if (!projectDate.value) return ''
  const date = new Date(projectDate.value)
  if (Number.isNaN(date.getTime())) return ''
  return new Intl.DateTimeFormat('zh-CN', {
    month: 'short',
    day: 'numeric',
  }).format(date)
})

watch(
  () => props.app.cover,
  () => {
    coverFailed.value = false
  },
)
</script>

<style scoped>
.app-card {
  min-width: 0;
  overflow: hidden;
  border: 1px solid var(--color-border);
  border-radius: 10px;
  background: var(--color-surface);
  transition:
    border-color 150ms ease,
    box-shadow 150ms ease;
}

.app-card:hover,
.app-card:focus-within {
  border-color: var(--color-primary-border);
  box-shadow: var(--shadow-card);
}

.app-preview {
  position: relative;
  display: block;
  overflow: hidden;
  aspect-ratio: 16 / 10;
  border-bottom: 1px solid var(--color-border);
  background: var(--color-primary-soft);
  color: inherit;
  text-decoration: none;
}

.app-preview img {
  display: block;
  width: 100%;
  height: 100%;
  object-fit: cover;
  object-position: top center;
}

.app-placeholder {
  display: grid;
  width: 100%;
  height: 100%;
  place-items: center;
  padding: 24px 30px 0;
  background: var(--color-surface-muted);
}

.mini-browser {
  width: 100%;
  align-self: end;
  overflow: hidden;
  border: 1px solid var(--color-primary-border);
  border-bottom: 0;
  border-radius: 7px 7px 0 0;
  background: var(--color-surface);
  box-shadow: var(--shadow-card);
}

.mini-browser-bar {
  display: flex;
  gap: 4px;
  padding: 9px 10px;
  border-bottom: 1px solid var(--color-border);
}

.mini-browser-bar i {
  width: 4px;
  height: 4px;
  border-radius: 50%;
  background: var(--color-border-strong);
}

.mini-browser-body {
  display: flex;
  min-height: 112px;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  padding: 15px;
  color: var(--color-primary-muted);
}

.mini-browser-body > span {
  display: -webkit-box;
  max-width: 100%;
  overflow: hidden;
  color: var(--color-primary);
  font-size: 12px;
  font-weight: 600;
  overflow-wrap: anywhere;
  -webkit-line-clamp: 1;
  -webkit-box-orient: vertical;
}

.mini-browser-body :deep(.anticon) {
  font-size: 20px;
}

.mini-browser-lines {
  display: flex;
  width: 68%;
  flex-direction: column;
  align-items: center;
  gap: 5px;
}

.mini-browser-lines i {
  width: 100%;
  height: 4px;
  border-radius: 3px;
  background: var(--color-surface-muted);
}

.mini-browser-lines i:last-child {
  width: 72%;
}

.project-status {
  position: absolute;
  top: 10px;
  right: 10px;
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 3px 8px;
  border: 1px solid var(--color-primary-border);
  border-radius: 5px;
  background: var(--color-success-bg);
  color: var(--color-success);
  font-size: 10px;
  line-height: 1.6;
}

.project-status > span {
  width: 4px;
  height: 4px;
  border-radius: 50%;
  background: var(--color-success);
}

.project-status.is-draft {
  border-color: var(--color-border);
  background: var(--color-surface);
  color: var(--color-text-muted);
}

.project-status.is-draft > span {
  background: var(--color-text-muted);
}

.app-info {
  padding: 17px 17px 0;
}

.app-title {
  overflow: hidden;
  margin: 0 0 12px;
  color: var(--color-text);
  font-size: 15px;
  font-weight: 650;
  line-height: 1.5;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.app-title a {
  color: inherit;
  text-decoration: none;
}

.app-title a:hover {
  color: var(--color-primary);
}

.app-meta {
  display: flex;
  min-width: 0;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  color: var(--color-text-muted);
  font-size: 11px;
}

.app-author {
  display: flex;
  min-width: 0;
  align-items: center;
  gap: 7px;
}

.app-author > span:last-child {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.app-meta time {
  flex: 0 0 auto;
}

.app-actions {
  display: flex;
  min-height: 44px;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  margin-top: 14px;
  border-top: 1px solid var(--color-border);
  font-size: 11px;
}

.app-actions a {
  display: inline-flex;
  min-height: 32px;
  align-items: center;
  gap: 6px;
  color: var(--color-primary);
  text-decoration: none;
}

.app-actions a:hover {
  color: var(--color-primary-hover);
}

.app-actions .edit-link {
  color: var(--color-primary-muted);
}

.public-caption {
  overflow: hidden;
  color: var(--color-text-muted);
  text-overflow: ellipsis;
  white-space: nowrap;
}

.app-actions > a:last-child {
  flex: 0 0 auto;
}

.app-card a:focus-visible {
  outline: 3px solid var(--color-focus);
  outline-offset: -3px;
}

@media (prefers-reduced-motion: reduce) {
  .app-card {
    transition: none;
  }
}
</style>
