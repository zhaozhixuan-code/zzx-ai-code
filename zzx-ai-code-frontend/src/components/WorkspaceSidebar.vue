<template>
  <aside class="workspace-sidebar">
    <RouterLink to="/" class="sidebar-brand" aria-label="AI 应用生成首页">
      <img src="@/assets/logo.png" alt="" width="32" height="32" />
      <span>AI 应用生成</span>
    </RouterLink>

    <RouterLink
      :to="{ path: '/', hash: '#create-app' }"
      class="new-project-link"
      aria-label="新建应用"
    >
      <PlusOutlined aria-hidden="true" />
      <span class="new-project-label">新建应用</span>
    </RouterLink>

    <nav class="primary-nav" aria-label="主导航">
      <RouterLink
        to="/"
        class="nav-link"
        :class="{ 'nav-link-active': isHomeActive }"
        :aria-current="isHomeActive ? 'page' : undefined"
      >
        <HomeOutlined aria-hidden="true" />
        <span>首页</span>
      </RouterLink>
      <RouterLink
        to="/my-projects"
        class="nav-link"
        :class="{ 'nav-link-active': isProjectsActive }"
        :aria-current="isProjectsActive ? 'page' : undefined"
      >
        <AppstoreOutlined aria-hidden="true" />
        <span>我的项目</span>
      </RouterLink>
    </nav>

    <section class="recent-section" aria-labelledby="recent-heading">
      <div class="section-heading">
        <h2 id="recent-heading">最近项目</h2>
        <ClockCircleOutlined aria-hidden="true" />
      </div>
      <div v-if="recentProjects.length" class="recent-list">
        <RouterLink
          v-for="project in recentProjects"
          :key="project.id"
          :to="`/app/chat/${project.id}`"
          class="recent-link"
          :class="{ 'recent-link-active': isActiveProject(project.id) }"
          :title="project.appName || '未命名应用'"
        >
          <span class="recent-mark" aria-hidden="true"></span>
          <span class="recent-name">{{ project.appName || '未命名应用' }}</span>
        </RouterLink>
      </div>
      <p v-else class="recent-empty">
        {{ isLoggedIn ? '还没有项目' : '登录后查看项目' }}
      </p>
    </section>

    <section v-if="isAdmin" class="admin-nav" aria-label="管理入口">
      <RouterLink to="/admin/appManage" class="nav-link">
        <AppstoreOutlined aria-hidden="true" />
        <span>应用管理</span>
      </RouterLink>
      <RouterLink to="/admin/userManage" class="nav-link">
        <UserOutlined aria-hidden="true" />
        <span>用户管理</span>
      </RouterLink>
    </section>

    <div class="account-area">
      <a-dropdown v-if="isLoggedIn" :trigger="['click']">
        <button type="button" class="account-control" aria-label="打开账户菜单">
          <a-avatar :src="loginUserStore.loginUser.userAvatar" :size="34">
            {{ loginUserStore.loginUser.userName?.charAt(0) || 'U' }}
          </a-avatar>
          <span class="account-name">{{ loginUserStore.loginUser.userName || '用户' }}</span>
          <DownOutlined class="account-chevron" aria-hidden="true" />
        </button>
        <template #overlay>
          <a-menu>
            <a-menu-item key="logout" @click="doLogout">
              <LogoutOutlined />
              退出登录
            </a-menu-item>
          </a-menu>
        </template>
      </a-dropdown>
      <button
        v-else
        type="button"
        class="account-control"
        aria-label="登录"
        @click="showLoginModal"
      >
        <a-avatar :size="34">
          <UserOutlined />
        </a-avatar>
        <span class="account-name">登录</span>
      </button>
    </div>
  </aside>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { message } from 'ant-design-vue'
import { listMyAppVoByPage } from '@/api/appController'
import { userLogout } from '@/api/userController'
import { useLoginUserStore } from '@/stores/loginUser'
import {
  AppstoreOutlined,
  ClockCircleOutlined,
  DownOutlined,
  HomeOutlined,
  LogoutOutlined,
  PlusOutlined,
  UserOutlined,
} from '@ant-design/icons-vue'

const route = useRoute()
const loginUserStore = useLoginUserStore()
const recentProjects = ref<API.AppVO[]>([])

const isLoggedIn = computed(() => !!loginUserStore.loginUser.id)
const isAdmin = computed(() => loginUserStore.loginUser.userRole === 'admin')
const isHomeActive = computed(() => route.path === '/')
const isProjectsActive = computed(() => route.path === '/my-projects')
const activeProjectId = computed(() => String(route.params.id ?? ''))

/** Load the signed-in user's six most recently updated projects for the sidebar. */
const loadRecentProjects = async (): Promise<void> => {
  if (!loginUserStore.loginUser.id) {
    recentProjects.value = []
    return
  }

  try {
    const res = await listMyAppVoByPage({
      pageNum: 1,
      pageSize: 6,
      sortField: 'createTime',
      sortOrder: 'desc',
    })
    recentProjects.value = res.data.code === 0 ? res.data.data?.records || [] : []
  } catch (error) {
    console.error('加载最近项目失败：', error)
    recentProjects.value = []
  }
}

/** Check whether a recent project matches the application currently open. */
const isActiveProject = (id: string | number | undefined): boolean => {
  return String(id ?? '') === activeProjectId.value
}

/** Open the global login dialog from the sidebar account area. */
const showLoginModal = (): void => {
  loginUserStore.openLoginModal()
}

/** Sign out the current user and report the result. */
const doLogout = async (): Promise<void> => {
  try {
    const res = await userLogout()
    if (res.data.code === 0) {
      loginUserStore.setLoginUser({ userName: '未登录' })
      message.success('退出登录成功')
    } else {
      message.error('退出登录失败，' + res.data.message)
    }
  } catch (error) {
    console.error('退出登录失败：', error)
    message.error('退出登录失败，请重试')
  }
}

watch([() => loginUserStore.loginUser.id, () => route.params.id], () => void loadRecentProjects(), {
  immediate: true,
})
</script>

<style scoped>
.workspace-sidebar {
  position: sticky;
  top: 0;
  display: flex;
  width: 248px;
  height: 100vh;
  flex: 0 0 248px;
  flex-direction: column;
  gap: 10px;
  padding: 20px 14px 12px;
  border-right: 1px solid var(--color-border);
  background: var(--color-sidebar);
}

.sidebar-brand {
  display: flex;
  min-height: 38px;
  align-items: center;
  gap: 10px;
  padding: 0 8px;
  color: var(--color-text);
  font-size: 15px;
  font-weight: 650;
  text-decoration: none;
}

.sidebar-brand img {
  flex: 0 0 auto;
  border-radius: 7px;
}

.new-project-link {
  display: flex;
  min-height: 40px;
  align-items: center;
  gap: 9px;
  margin-top: 8px;
  padding: 0 12px;
  border: 1px solid var(--color-primary);
  border-radius: 8px;
  background: var(--color-primary);
  color: var(--color-surface);
  font-size: 14px;
  font-weight: 600;
  text-decoration: none;
  transition:
    border-color 150ms ease,
    background-color 150ms ease;
}

.new-project-link:hover {
  border-color: var(--color-primary-hover);
  background: var(--color-primary-hover);
  color: var(--color-surface);
}

.primary-nav,
.admin-nav {
  display: flex;
  flex-direction: column;
  gap: 3px;
}

.primary-nav {
  margin-top: 5px;
}

.nav-link {
  display: flex;
  min-height: 38px;
  align-items: center;
  gap: 11px;
  padding: 0 11px;
  border-radius: 8px;
  color: var(--color-text-secondary);
  font-size: 14px;
  text-decoration: none;
  transition:
    background-color 140ms ease,
    color 140ms ease;
}

.nav-link :deep(.anticon) {
  color: var(--color-text-muted);
  font-size: 16px;
}

.nav-link:hover {
  background: var(--color-surface-muted);
  color: var(--color-text);
}

.nav-link-active,
.nav-link-active:hover {
  background: var(--color-primary-soft);
  color: var(--color-primary);
  box-shadow: inset 3px 0 0 var(--color-primary);
  font-weight: 600;
}

.nav-link-active :deep(.anticon) {
  color: var(--color-primary);
}

.recent-section {
  min-height: 0;
  flex: 1;
  margin-top: 8px;
}

.section-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 9px 10px 7px;
  color: var(--color-text-muted);
}

.section-heading h2 {
  margin: 0;
  font-size: 12px;
  font-weight: 650;
}

.section-heading :deep(.anticon) {
  font-size: 13px;
}

.recent-list {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.recent-link {
  display: flex;
  min-width: 0;
  min-height: 34px;
  align-items: center;
  gap: 9px;
  padding: 0 10px;
  border-radius: 5px;
  color: var(--color-text-secondary);
  font-size: 13px;
  text-decoration: none;
}

.recent-link:hover {
  background: var(--color-surface-muted);
  color: var(--color-text);
}

.recent-link-active {
  background: var(--color-primary-soft);
  color: var(--color-primary);
  font-weight: 600;
}

.recent-mark {
  width: 7px;
  height: 7px;
  flex: 0 0 auto;
  border: 1.5px solid var(--color-text-muted);
  border-radius: 2px;
}

.recent-link-active .recent-mark {
  border-color: var(--color-primary);
  background: var(--color-primary);
}

.recent-name {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.recent-empty {
  margin: 2px 10px;
  color: var(--color-text-muted);
  font-size: 12px;
}

.admin-nav {
  padding-top: 10px;
  border-top: 1px solid var(--color-border);
}

.account-area {
  margin-top: auto;
  padding-top: 10px;
  border-top: 1px solid var(--color-border);
}

.account-control {
  display: flex;
  width: 100%;
  min-height: 46px;
  align-items: center;
  gap: 10px;
  padding: 5px 8px;
  border: 0;
  border-radius: 6px;
  background: transparent;
  color: var(--color-text);
  cursor: pointer;
  font: inherit;
  text-align: left;
}

.account-control:hover {
  background: var(--color-surface-muted);
}

.account-name {
  min-width: 0;
  flex: 1;
  overflow: hidden;
  font-size: 13px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.account-chevron {
  color: var(--color-text-muted);
  font-size: 12px;
}

.workspace-sidebar :focus-visible {
  outline: 3px solid var(--color-focus);
  outline-offset: 2px;
}

@media (max-width: 760px) {
  .workspace-sidebar {
    position: relative;
    width: 100%;
    height: auto;
    min-height: 0;
    flex: 0 0 auto;
    flex-direction: row;
    flex-wrap: wrap;
    align-items: center;
    gap: 7px 10px;
    padding: 10px 12px 8px;
    border-right: 0;
    border-bottom: 1px solid var(--color-border);
  }

  .sidebar-brand {
    min-height: 36px;
    flex: 1 1 auto;
    padding-left: 2px;
  }

  .new-project-link {
    width: 38px;
    min-height: 36px;
    justify-content: center;
    margin: 0;
    padding: 0;
  }

  .new-project-label {
    display: none;
  }

  .primary-nav {
    order: 2;
    width: 100%;
    flex-direction: row;
    margin: 0;
  }

  .nav-link {
    min-height: 34px;
    gap: 7px;
    padding: 0 9px;
    font-size: 13px;
  }

  .recent-section {
    order: 3;
    width: 100%;
    flex: 0 0 auto;
    margin: 0;
  }

  .section-heading {
    display: flex;
    padding: 0 8px 2px;
  }

  .recent-list {
    flex-direction: row;
    overflow-x: auto;
    scrollbar-width: thin;
  }

  .recent-link {
    max-width: 180px;
    min-height: 30px;
    flex: 0 0 auto;
    padding: 0 8px;
  }

  .admin-nav {
    order: 4;
    width: 100%;
    flex-direction: row;
    padding-top: 6px;
  }

  .account-area {
    order: 1;
    flex: 0 0 auto;
    margin: 0;
    padding: 0;
    border: 0;
  }

  .account-control {
    width: auto;
    min-height: 36px;
    padding: 1px 3px;
  }

  .account-control :deep(.ant-avatar) {
    width: 30px;
    height: 30px;
    line-height: 30px;
  }

  .account-name,
  .account-chevron {
    display: none;
  }
}

@media (prefers-reduced-motion: reduce) {
  .new-project-link,
  .nav-link {
    transition: none;
  }
}
</style>
