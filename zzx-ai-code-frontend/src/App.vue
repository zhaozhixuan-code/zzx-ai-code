<script setup lang="ts">
import BasicLayout from '@/layouts/BasicLayout.vue'
import LoginModal from '@/components/LoginModal.vue'
import { useLoginUserStore } from '@/stores/loginUser'
import { storeToRefs } from 'pinia'
import { appTheme } from '@/styles/theme'

const loginUserStore = useLoginUserStore()
const { loginModalVisible } = storeToRefs(loginUserStore)

const handleLoginSuccess = () => {
  // 登录成功后的逻辑
}
</script>

<template>
  <a-config-provider :theme="appTheme">
    <BasicLayout />
    <!-- 全局登录弹窗也使用工作区主题。 -->
    <LoginModal v-model:visible="loginModalVisible" @success="handleLoginSuccess" />
  </a-config-provider>
</template>

<style>
/* 全局样式优化 */
* {
  box-sizing: border-box;
}

body {
  margin: 0;
  padding: 0;
  font-family: var(--font-ui);
  -webkit-font-smoothing: antialiased;
  -moz-osx-font-smoothing: grayscale;
  overflow-x: hidden;
}

html {
  overflow-x: hidden;
}
</style>
