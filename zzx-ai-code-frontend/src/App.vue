<script setup lang="ts">
import BasicLayout from '@/layouts/BasicLayout.vue'
import LoginModal from '@/components/LoginModal.vue'
import { useLoginUserStore } from '@/stores/loginUser'
import { storeToRefs } from 'pinia'

const loginUserStore = useLoginUserStore()
const { loginModalVisible } = storeToRefs(loginUserStore)

const handleLoginSuccess = () => {
  // 登录成功后的逻辑
}
</script>

<template>
  <BasicLayout />
  <!-- 全局登录弹窗 -->
  <LoginModal
    v-model:visible="loginModalVisible"
    @success="handleLoginSuccess"
  />
</template>

<style>
/* 全局样式优化 */
* {
  box-sizing: border-box;
}

body {
  margin: 0;
  padding: 0;
  font-family:
    -apple-system, BlinkMacSystemFont, 'Segoe UI', 'Roboto', 'Oxygen', 'Ubuntu', 'Cantarell',
    'Fira Sans', 'Droid Sans', 'Helvetica Neue', sans-serif;
  -webkit-font-smoothing: antialiased;
  -moz-osx-font-smoothing: grayscale;
  overflow-x: hidden;
}

html {
  overflow-x: hidden;
}
</style>
