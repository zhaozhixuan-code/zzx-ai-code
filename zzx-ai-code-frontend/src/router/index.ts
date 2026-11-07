import { createRouter, createWebHistory } from 'vue-router'
import HomePage from '@/pages/HomePage.vue'
import UserLoginPage from '@/pages/user/UserLoginPage.vue'
import UserRegisterPage from '@/pages/user/UserRegisterPage.vue'
import UserManagePage from '@/pages/admin/UserManagePage.vue'
import AppManagePage from '@/pages/admin/AppManagePage.vue'
import AppChatPage from '@/pages/app/AppChatPage.vue'
import AppEditPage from '@/pages/app/AppEditPage.vue'
import MyProjectsPage from '@/pages/app/MyProjectsPage.vue'
import ChatManagePage from '@/pages/admin/ChatManagePage.vue'

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    {
      path: '/',
      name: '主页',
      component: HomePage,
    },
    {
      path: '/my-projects',
      name: '我的项目',
      component: MyProjectsPage,
    },
    {
      path: '/user/login',
      name: '用户登录',
      component: UserLoginPage,
    },
    {
      path: '/user/register',
      name: '用户注册',
      component: UserRegisterPage,
    },
    {
      path: '/admin/userManage',
      name: '用户管理',
      component: UserManagePage,
    },
    {
      path: '/admin/appManage',
      name: '应用管理',
      component: AppManagePage,
    },
    {
      path: '/app/chat/:id',
      name: '应用对话',
      component: AppChatPage,
      meta: { fullScreen: true },
    },
    {
      path: '/app/edit/:id',
      name: '编辑应用',
      component: AppEditPage,
    },
    {
      path: '/admin/chatManage',
      name: '对话管理',
      component: ChatManagePage,
    },
  ],
  /**
   * Restore saved scroll positions or reveal a section linked from the workspace sidebar.
   * @param to The destination route.
   * @param _from The route being left.
   * @param savedPosition A browser-restored scroll position, when available.
   * @returns The scroll position or anchor options selected for the destination.
   */
  scrollBehavior(to, _from, savedPosition) {
    if (savedPosition) {
      return savedPosition
    }
    if (to.hash) {
      return { el: to.hash, behavior: 'smooth' }
    }
    return { top: 0 }
  },
})

export default router
