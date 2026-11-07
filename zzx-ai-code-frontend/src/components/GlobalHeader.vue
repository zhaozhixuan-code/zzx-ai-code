<template>
  <a-layout-header class="header">
    <a-row :wrap="false">
      <!-- 左侧：Logo和标题 -->
      <a-col flex="200px">
        <RouterLink to="/">
          <div class="header-left">
            <img class="logo" src="@/assets/logo.png" alt="AI 应用生成" />
            <h1 class="site-title">AI 应用生成</h1>
          </div>
        </RouterLink>
      </a-col>
      <!-- 中间：导航菜单 -->
      <a-col flex="auto" class="desktop-nav">
        <a-menu
          v-model:selectedKeys="selectedKeys"
          mode="horizontal"
          :items="menuItems"
          @click="handleMenuClick"
        />
      </a-col>
      <a-col flex="none" class="mobile-nav">
        <a-dropdown>
          <a-button class="mobile-menu-button" aria-label="打开导航菜单" title="导航菜单">
            <MenuOutlined />
          </a-button>
          <template #overlay>
            <a-menu :items="menuItems" @click="handleMenuClick" />
          </template>
        </a-dropdown>
      </a-col>
      <!-- 右侧：用户操作区域 -->
      <a-col>
        <div class="user-login-status">
          <div v-if="loginUserStore.loginUser.id" class="user-info">
            <a-dropdown>
              <a-space class="user-trigger">
                <a-avatar :src="loginUserStore.loginUser.userAvatar" size="small" />
                <span class="user-name">{{ loginUserStore.loginUser.userName ?? '无名' }}</span>
                <DownOutlined />
              </a-space>
              <template #overlay>
                <a-menu>
                  <a-menu-item key="logout" @click="doLogout">
                    <LogoutOutlined />
                    退出登录
                  </a-menu-item>
                </a-menu>
              </template>
            </a-dropdown>
          </div>
          <div v-else>
            <a-button type="primary" class="login-btn" @click="showLoginModal"> 登录 </a-button>
          </div>
        </div>
      </a-col>
    </a-row>
  </a-layout-header>
</template>

<script setup lang="ts">
import { computed, h, ref } from 'vue'
import { useRouter } from 'vue-router'
import { type MenuProps, message } from 'ant-design-vue'
import { useLoginUserStore } from '@/stores/loginUser'
import { userLogout } from '@/api/userController'
import { LogoutOutlined, HomeOutlined, DownOutlined, MenuOutlined } from '@ant-design/icons-vue'

const loginUserStore = useLoginUserStore()
const router = useRouter()

// 打开登录弹窗（使用全局状态）
const showLoginModal = () => {
  loginUserStore.openLoginModal()
}

// 当前选中菜单
const selectedKeys = ref<string[]>(['/'])
// 监听路由变化，更新当前选中菜单
router.afterEach((to) => {
  selectedKeys.value = [to.path]
})

// 菜单配置项
const originItems = [
  {
    key: '/',
    icon: () => h(HomeOutlined),
    label: '主页',
    title: '主页',
  },
  {
    key: '/admin/userManage',
    label: '用户管理',
    title: '用户管理',
  },
  {
    key: '/admin/appManage',
    label: '应用管理',
    title: '应用管理',
  },
]

// 过滤菜单项
const filterMenus = (menus = [] as MenuProps['items']) => {
  return menus?.filter((menu) => {
    const menuKey = menu?.key as string
    if (menuKey?.startsWith('/admin')) {
      const loginUser = loginUserStore.loginUser
      if (!loginUser || loginUser.userRole !== 'admin') {
        return false
      }
    }
    return true
  })
}

// 展示在菜单的路由数组
const menuItems = computed<MenuProps['items']>(() => filterMenus(originItems))

// 处理菜单点击
const handleMenuClick: MenuProps['onClick'] = (e) => {
  const key = e.key as string
  selectedKeys.value = [key]
  // 跳转到对应页面
  if (key.startsWith('/')) {
    router.push(key)
  }
}

// 退出登录
const doLogout = async () => {
  const res = await userLogout()
  if (res.data.code === 0) {
    loginUserStore.setLoginUser({
      userName: '未登录',
    })
    message.success('退出登录成功')
  } else {
    message.error('退出登录失败，' + res.data.message)
  }
}
</script>

<style scoped>
.header {
  position: sticky;
  top: 0;
  z-index: 100;
  height: 64px;
  padding: 0 30px;
  border-bottom: 1px solid #e1e6ea;
  background: #fff;
  line-height: 64px;
}

.header-left {
  display: flex;
  align-items: center;
  gap: 10px;
}

.logo {
  width: 34px;
  height: 34px;
  border-radius: 7px;
  object-fit: contain;
}

.site-title {
  margin: 0;
  color: #1d2935;
  font-size: 17px;
  font-weight: 650;
}

.ant-menu-horizontal {
  border-bottom: 0;
  line-height: 62px;
}

.ant-menu-horizontal :deep(.ant-menu-item) {
  height: 62px;
  line-height: 62px;
  color: #53616d;
}

.ant-menu-horizontal :deep(.ant-menu-item:hover),
.ant-menu-horizontal :deep(.ant-menu-item-selected) {
  color: #2858c7;
}

.ant-menu-horizontal :deep(.ant-menu-item-selected)::after {
  border-bottom-color: #2858c7;
}

.user-login-status {
  display: flex;
  align-items: center;
}

.user-info {
  cursor: pointer;
}

.user-trigger {
  padding: 4px 10px;
  border-radius: 6px;
  transition: background-color 150ms ease;
}

.user-trigger:hover {
  background: #f1f4f6;
}

.user-name {
  max-width: 112px;
  overflow: hidden;
  color: #36434e;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.login-btn {
  height: 36px;
  padding: 0 16px;
  border-radius: 6px;
  background: #2858c7;
  font-weight: 600;
}

.mobile-nav {
  display: none;
}

@media (max-width: 720px) {
  .header {
    height: 58px;
    padding: 0 16px;
    line-height: 58px;
  }

  .logo {
    width: 30px;
    height: 30px;
  }

  .site-title {
    font-size: 15px;
  }

  .desktop-nav {
    display: none;
  }

  .mobile-nav {
    display: flex;
    align-items: center;
  }

  .mobile-menu-button {
    width: 36px;
    height: 36px;
    padding: 0;
    border-color: #dce3e8;
    border-radius: 6px;
    color: #36434e;
  }
}

@media (prefers-reduced-motion: reduce) {
  .user-trigger {
    transition: none;
  }
}

@media (max-width: 420px) {
  .header :deep(.user-name) {
    display: none;
  }
}
</style>
