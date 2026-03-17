import { defineStore } from 'pinia'
import { ref } from 'vue'
import { getLoginUser } from '@/api/userController.ts'

/**
 * 登录用户信息
 */
export const useLoginUserStore = defineStore('loginUser', () => {
  // 默认值
  const loginUser = ref<API.LoginUserVO>({
    userName: '未登录',
  })

  // 全局登录弹窗状态
  const loginModalVisible = ref(false)
  const loginModalRedirect = ref('')

  // 获取登录用户信息
  async function fetchLoginUser() {
    const res = await getLoginUser()
    if (res.data.code === 0 && res.data.data) {
      loginUser.value = res.data.data
    }
  }

  // 更新登录用户信息
  function setLoginUser(newLoginUser: any) {
    loginUser.value = newLoginUser
  }

  // 打开登录弹窗
  function openLoginModal(redirect?: string) {
    loginModalVisible.value = true
    loginModalRedirect.value = redirect || ''
  }

  // 关闭登录弹窗
  function closeLoginModal() {
    loginModalVisible.value = false
    loginModalRedirect.value = ''
  }

  return {
    loginUser,
    loginModalVisible,
    loginModalRedirect,
    fetchLoginUser,
    setLoginUser,
    openLoginModal,
    closeLoginModal,
  }
})
