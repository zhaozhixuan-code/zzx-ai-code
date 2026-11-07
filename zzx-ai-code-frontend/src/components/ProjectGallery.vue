<template>
  <div class="project-gallery" :aria-busy="loading">
    <div v-if="loading" class="gallery-grid" aria-label="正在加载项目">
      <div v-for="item in 6" :key="item" class="skeleton-card" aria-hidden="true">
        <div class="skeleton-cover"></div>
        <div class="skeleton-line"></div>
        <div class="skeleton-line skeleton-line-short"></div>
      </div>
    </div>
    <div v-else-if="error" class="gallery-state" role="status">
      <ExclamationCircleOutlined aria-hidden="true" />
      <h3>暂时无法加载项目</h3>
      <p>{{ error }}</p>
      <button type="button" class="retry-button" @click="emit('retry')">重新加载</button>
    </div>
    <div v-else-if="projects.length === 0" class="gallery-state">
      <FolderOpenOutlined aria-hidden="true" />
      <h3>{{ emptyTitle }}</h3>
      <p>{{ emptyDescription }}</p>
      <slot name="empty-action"></slot>
    </div>
    <div v-else class="gallery-grid">
      <AppCard v-for="app in projects" :key="app.id" :app="app" :mode="mode" />
    </div>
  </div>
</template>

<script setup lang="ts">
import { ExclamationCircleOutlined, FolderOpenOutlined } from '@ant-design/icons-vue'
import AppCard from '@/components/AppCard.vue'

withDefaults(
  defineProps<{
    projects: API.AppVO[]
    loading: boolean
    error: string
    mode?: 'public' | 'personal'
    emptyTitle: string
    emptyDescription: string
  }>(),
  { mode: 'personal' },
)
const emit = defineEmits<{ retry: [] }>()
</script>

<style scoped>
.project-gallery {
  container-type: inline-size;
}

.gallery-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 22px;
}

.gallery-state {
  display: flex;
  min-height: 240px;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 10px;
  padding: 32px 20px;
  border: 1px dashed #d4dfdd;
  border-radius: 12px;
  background: #fcfdfd;
  color: #67807a;
  text-align: center;
}

.gallery-state > :deep(.anticon) {
  margin-bottom: 4px;
  font-size: 30px;
}

.gallery-state h3 {
  margin: 0;
  color: #2b3d39;
  font-size: 17px;
  font-weight: 600;
}

.gallery-state p {
  max-width: 440px;
  margin: 0;
  color: #6b7b77;
  font-size: 14px;
  overflow-wrap: anywhere;
}

.retry-button {
  margin-top: 4px;
  padding: 7px 16px;
  border: 1px solid #cad7d4;
  border-radius: 6px;
  background: #fff;
  color: #2858c7;
  cursor: pointer;
  font: inherit;
}

.retry-button:hover {
  background: #eff5f3;
}

.retry-button:focus-visible {
  outline: 3px solid #a5bcf0;
  outline-offset: 3px;
}

.skeleton-card {
  overflow: hidden;
  padding-bottom: 18px;
  border: 1px solid #e2e9e6;
  border-radius: 10px;
  background: #fff;
}

.skeleton-cover {
  aspect-ratio: 16 / 10;
  margin-bottom: 18px;
  background: #eaf0ed;
}

.skeleton-line {
  width: 64%;
  height: 12px;
  margin: 10px 16px 0;
  border-radius: 3px;
  background: #eaf0ed;
}

.skeleton-line-short {
  width: 36%;
}

@container (max-width: 880px) {
  .gallery-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 18px;
  }
}

@container (max-width: 520px) {
  .gallery-grid {
    grid-template-columns: minmax(0, 1fr);
    gap: 16px;
  }
}
</style>
