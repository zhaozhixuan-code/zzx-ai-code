<template>
  <a-modal
    :open="visible"
    :footer="null"
    :closable="false"
    :centered="true"
    :width="380"
    class="login-modal"
    :maskClosable="false"
    @cancel="handleClose"
  >
    <div class="modal-container">
      <!-- 关闭按钮 -->
      <div class="close-btn" @click="handleClose">
        <CloseOutlined />
      </div>

      <!-- 标题区域 -->
      <div class="modal-header">
        <div class="logo-wrapper">
          <img src="@/assets/logo.png" alt="Logo" class="modal-logo" />
        </div>
        <h2 class="modal-title">{{ isLogin ? '登录' : '注册' }}</h2>
        <p class="modal-desc">{{ isLogin ? '发现更多精彩内容' : '开始你的分享之旅' }}</p>
      </div>

      <!-- 登录表单 -->
      <a-form
        v-if="isLogin"
        :model="loginForm"
        layout="vertical"
        @finish="handleLogin"
      >
        <a-form-item name="userAccount" :rules="[{ required: true, message: '请输入账号' }]">
          <a-input
            v-model:value="loginForm.userAccount"
            placeholder="账号"
            size="large"
          />
        </a-form-item>
        <a-form-item
          name="userPassword"
          :rules="[
              { required: true, message: '请输入密码' },
              { min: 8, message: '密码长度不能小于 8 位' },
            ]"
        >
          <a-input-password
            v-model:value="loginForm.userPassword"
            placeholder="密码"
            size="large"
          />
        </a-form-item>
        <a-form-item>
          <a-button type="primary" html-type="submit" size="large" block :loading="loginLoading" class="submit-btn">
            登录
          </a-button>
        </a-form-item>
      </a-form>

      <!-- 注册表单 -->
      <a-form
        v-else
        :model="registerForm"
        layout="vertical"
        @finish="handleRegister"
      >
        <a-form-item name="userAccount" :rules="[{ required: true, message: '请输入账号' }]">
          <a-input
            v-model:value="registerForm.userAccount"
            placeholder="账号"
            size="large"
          />
        </a-form-item>
        <a-form-item
          name="userPassword"
          :rules="[
              { required: true, message: '请输入密码' },
              { min: 8, message: '密码不能小于 8 位' },
            ]"
        >
          <a-input-password
            v-model:value="registerForm.userPassword"
            placeholder="密码"
            size="large"
          />
        </a-form-item>
        <a-form-item
          name="checkPassword"
          :rules="[
              { required: true, message: '请确认密码' },
              { min: 8, message: '密码不能小于 8 位' },
              { validator: validateCheckPassword },
            ]"
        >
          <a-input-password
            v-model:value="registerForm.checkPassword"
            placeholder="确认密码"
            size="large"
          />
        </a-form-item>
        <a-form-item>
          <a-button type="primary" html-type="submit" size="large" block :loading="registerLoading" class="submit-btn">
            注册
          </a-button>
        </a-form-item>
      </a-form>

      <!-- 切换登录/注册 -->
      <div class="switch-mode">
          <span class="switch-text">
            {{ isLogin ? '还没有账号？' : '已有账号？' }}
          </span>
        <a-button type="link" class="switch-btn" @click="toggleMode">
          {{ isLogin ? '立即注册' : '立即登录' }}
        </a-button>
      </div>
    </div>
  </a-modal>
</template>

<script setup lang="ts">
import { ref, reactive, watch } from 'vue'
import { message } from 'ant-design-vue'
import { CloseOutlined } from '@ant-design/icons-vue'
import { userLogin, userRegister } from '@/api/userController'
import { useLoginUserStore } from '@/stores/loginUser'

const props = defineProps<{
  visible: boolean
}>()

const emit = defineEmits<{
  'update:visible': [value: boolean]
  'success': []
}>()

const loginUserStore = useLoginUserStore()

// 模式：true 为登录，false 为注册
const isLogin = ref(true)
const loginLoading = ref(false)
const registerLoading = ref(false)

// 登录表单
const loginForm = reactive({
  userAccount: '',
  userPassword: '',
})

// 注册表单
const registerForm = reactive({
  userAccount: '',
  userPassword: '',
  checkPassword: '',
})

// 监听弹窗打开，重置表单
watch(() => props.visible, (newVal) => {
  if (newVal) {
    isLogin.value = true
    loginForm.userAccount = ''
    loginForm.userPassword = ''
    registerForm.userAccount = ''
    registerForm.userPassword = ''
    registerForm.checkPassword = ''
  }
})

// 同步全局登录弹窗状态
watch(() => loginUserStore.loginModalVisible, (newVal) => {
  if (newVal && !props.visible) {
    emit('update:visible', true)
  }
})

// 验证确认密码
const validateCheckPassword = (rule: unknown, value: string, callback: (error?: Error) => void) => {
  if (value && value !== registerForm.userPassword) {
    callback(new Error('两次输入密码不一致'))
  } else {
    callback()
  }
}

// 切换登录/注册模式
const toggleMode = () => {
  isLogin.value = !isLogin.value
}

// 关闭弹窗
const handleClose = () => {
  emit('update:visible', false)
  loginUserStore.closeLoginModal()
}

// 登录
const handleLogin = async () => {
  loginLoading.value = true
  try {
    const res = await userLogin(loginForm)
    if (res.data.code === 0 && res.data.data) {
      await loginUserStore.fetchLoginUser()
      message.success('登录成功')
      handleClose()
      emit('success')
    } else {
      message.error('登录失败，' + res.data.message)
    }
  } finally {
    loginLoading.value = false
  }
}

// 注册
const handleRegister = async () => {
  registerLoading.value = true
  try {
    const res = await userRegister(registerForm)
    if (res.data.code === 0) {
      message.success('注册成功，请登录')
      isLogin.value = true
    } else {
      message.error('注册失败，' + res.data.message)
    }
  } finally {
    registerLoading.value = false
  }
}
</script>

<style scoped>
.login-modal :deep(.ant-modal-content) {
  border-radius: 16px;
  overflow: hidden;
  box-shadow: 0 8px 40px rgba(0, 0, 0, 0.12);
}

.login-modal :deep(.ant-modal-body) {
  padding: 0;
}

.modal-container {
  padding: 40px 32px 32px;
  position: relative;
  background: #fff;
}

.close-btn {
  position: absolute;
  top: 16px;
  right: 16px;
  font-size: 20px;
  color: #999;
  cursor: pointer;
  transition: all 0.3s;
  width: 32px;
  height: 32px;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 50%;
}

.close-btn:hover {
  color: #333;
  background: #f5f5f5;
}

.modal-header {
  text-align: center;
  margin-bottom: 32px;
}

.logo-wrapper {
  display: flex;
  justify-content: center;
  margin-bottom: 16px;
}

.modal-logo {
  width: 56px;
  height: 56px;
  border-radius: 12px;
  box-shadow: 0 4px 12px rgba(24, 144, 255, 0.15);
}

.modal-title {
  font-size: 22px;
  font-weight: 600;
  color: #1a1a1a;
  margin: 0 0 8px;
  letter-spacing: -0.5px;
}

.modal-desc {
  font-size: 14px;
  color: #999;
  margin: 0;
}

.login-modal :deep(.ant-input-affix-wrapper) {
  border-radius: 10px;
  padding: 12px 16px;
  border: 1px solid #e5e5e5;
  background: #fafafa;
  transition: all 0.3s;
}

.login-modal :deep(.ant-input-affix-wrapper:hover),
.login-modal :deep(.ant-input-affix-wrapper-focused) {
  border-color: #1890ff;
  background: #fff;
  box-shadow: 0 0 0 2px rgba(24, 144, 255, 0.1);
}

.login-modal :deep(.ant-input) {
  font-size: 15px;
}

.login-modal :deep(.ant-input::placeholder) {
  color: #bbb;
}

.login-modal :deep(.ant-input-password-icon) {
  color: #bbb;
}

.login-modal :deep(.ant-input-password-icon:hover) {
  color: #1890ff;
}

.login-modal :deep(.ant-form-item) {
  margin-bottom: 16px;
}

.login-modal :deep(.ant-form-item:last-child) {
  margin-bottom: 0;
  margin-top: 8px;
}

.submit-btn {
  border-radius: 10px;
  height: 48px;
  font-size: 16px;
  font-weight: 500;
  background: #1890ff;
  border-color: #1890ff;
  transition: all 0.3s;
}

.submit-btn:hover {
  background: #40a9ff;
  border-color: #40a9ff;
  transform: translateY(-1px);
  box-shadow: 0 4px 12px rgba(24, 144, 255, 0.35);
}

.submit-btn:active {
  transform: translateY(0);
}

.login-modal :deep(.ant-btn-primary[disabled]) {
  background: #91d5ff;
  border-color: #91d5ff;
}

.switch-mode {
  text-align: center;
  margin-top: 20px;
}

.switch-text {
  font-size: 14px;
  color: #999;
}

.switch-btn {
  padding: 0 4px;
  font-size: 14px;
  color: #1890ff;
  font-weight: 500;
}

.switch-btn:hover {
  color: #40a9ff;
}

/* 手机号/账号输入框特殊样式 */
.login-modal :deep(.ant-input-number) {
  width: 100%;
  border-radius: 10px;
}

.login-modal :deep(.ant-input-number:hover),
.login-modal :deep(.ant-input-number-focused) {
  border-color: #1890ff;
  box-shadow: 0 0 0 2px rgba(24, 144, 255, 0.1);
}
</style>
