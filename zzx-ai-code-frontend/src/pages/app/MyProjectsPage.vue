<template>
  <div id="myProjectsPage">
    <div class="projects-container">
      <header class="page-heading">
        <div>
          <h1>我的项目</h1>
          <p>每一个想法的进展，都在这里。</p>
        </div>
        <RouterLink :to="{ path: '/', hash: '#create-app' }" class="create-project">
          <PlusOutlined aria-hidden="true" />新建应用
        </RouterLink>
      </header>

      <div v-if="!isLoggedIn" class="login-state">
        <LockOutlined aria-hidden="true" />
        <h2>登录后查看你的项目</h2>
        <p>草稿、已部署的网站，都可以在这里继续编辑。</p>
        <a-button type="primary" @click="showLogin">登录</a-button>
      </div>
      <template v-else>
        <div class="catalog-summary">
          <span>全部项目</span>
          <span v-if="!loading && !error" class="project-total">共 {{ total }} 个</span>
        </div>
        <ProjectGallery
          :projects="projects"
          :loading="loading"
          :error="error"
          mode="personal"
          empty-title="第一个项目，从一个想法开始"
          empty-description="创建应用后，可以在这里查看和管理它。"
          @retry="reload"
        >
          <template #empty-action>
            <RouterLink :to="{ path: '/', hash: '#create-app' }" class="empty-create">
              创建第一个应用
            </RouterLink>
          </template>
        </ProjectGallery>
        <div v-if="total > pageSize && !loading && !error" class="pagination-wrapper">
          <a-pagination
            :current="currentPage"
            :page-size="pageSize"
            :total="total"
            :show-size-changer="false"
            @change="changePage"
          />
        </div>
      </template>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { LockOutlined, PlusOutlined } from '@ant-design/icons-vue'
import { listMyAppVoByPage } from '@/api/appController'
import ProjectGallery from '@/components/ProjectGallery.vue'
import { useProjectCatalog } from '@/composables/useProjectCatalog'
import { useLoginUserStore } from '@/stores/loginUser'

const loginUserStore = useLoginUserStore()
const userScope = computed(() => String(loginUserStore.loginUser.id || ''))
const isLoggedIn = computed(() => !!userScope.value)
const { projects, total, currentPage, pageSize, loading, error, reload, changePage } =
  useProjectCatalog(loadMyProjects, userScope)

/**
 * 分页读取当前登录用户的全部项目，包含未部署的草稿。
 * @param pageNum 当前页号，从 1 开始。
 * @param pageSize 每页记录数，不超过后端的 20 条限制。
 * @returns 我的项目列表响应；网络错误交由公共列表状态处理。
 */
function loadMyProjects(pageNum: number, pageSize: number) {
  return listMyAppVoByPage({
    pageNum,
    pageSize,
    sortField: 'createTime',
    sortOrder: 'descend',
  })
}

/** 从项目列表打开登录弹窗；登录完成后用户范围监听会自动刷新列表。 */
function showLogin(): void {
  loginUserStore.openLoginModal()
}
</script>

<style scoped>
#myProjectsPage {
  min-height: 100%;
  background: var(--color-page);
  color: var(--color-text);
}

.projects-container {
  width: min(calc(100% - 80px), 1120px);
  margin: 0 auto;
  padding: 50px 0 64px;
}

.page-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 20px;
  margin-bottom: 38px;
}

.page-heading h1 {
  margin: 0 0 10px;
  font-size: 30px;
  font-weight: 650;
  line-height: 1.4;
}

.page-heading p {
  margin: 0;
  color: var(--color-text-muted);
  font-size: 14px;
}

.create-project {
  display: inline-flex;
  min-height: 38px;
  flex: 0 0 auto;
  align-items: center;
  gap: 8px;
  padding: 0 14px;
  border-radius: 7px;
  background: var(--color-primary);
  color: var(--color-surface);
  font-size: 13px;
  text-decoration: none;
}

.create-project:hover {
  background: var(--color-primary-hover);
}

.catalog-summary {
  display: flex;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 22px;
  padding-bottom: 14px;
  border-bottom: 1px solid var(--color-border);
  font-size: 14px;
  font-weight: 600;
}

.project-total {
  color: var(--color-text-muted);
  font-size: 12px;
  font-weight: 400;
  font-variant-numeric: tabular-nums;
}

.login-state {
  display: flex;
  min-height: 300px;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 12px;
  padding: 32px 20px;
  border: 1px dashed var(--color-border-strong);
  border-radius: 12px;
  background: var(--color-primary-surface);
  text-align: center;
}

.login-state > :deep(.anticon) {
  margin-bottom: 4px;
  color: var(--color-primary);
  font-size: 30px;
}

.login-state h2 {
  margin: 0;
  font-size: 19px;
  font-weight: 600;
}

.login-state p {
  margin: 0 0 6px;
  color: var(--color-text-muted);
  font-size: 14px;
}

.empty-create {
  display: inline-flex;
  min-height: 36px;
  align-items: center;
  padding: 0 14px;
  border: 1px solid var(--color-border-strong);
  border-radius: 6px;
  background: var(--color-surface);
  color: var(--color-primary);
  font-size: 13px;
  text-decoration: none;
}

.empty-create:hover {
  background: var(--color-primary-soft);
}

.projects-container a:focus-visible {
  outline: 3px solid var(--color-focus);
  outline-offset: 3px;
}

.pagination-wrapper {
  display: flex;
  justify-content: center;
  margin-top: 30px;
}

@media (max-width: 1100px) {
  .projects-container {
    width: calc(100% - 48px);
    padding-top: 36px;
  }
}

@media (max-width: 600px) {
  .projects-container {
    width: calc(100% - 32px);
    padding: 28px 0 40px;
  }

  .page-heading {
    align-items: start;
    flex-direction: column;
    gap: 18px;
    margin-bottom: 28px;
  }
}
</style>
