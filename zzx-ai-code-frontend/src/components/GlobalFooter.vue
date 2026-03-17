<template>
  <a-layout-footer class="footer">
    <div class="footer-content">
      <div class="footer-main">
        <div class="footer-brand">
          <img class="footer-logo" src="@/assets/logo.png" alt="Logo" />
          <span class="footer-title">AI 应用生成</span>
        </div>
        <div class="footer-links">
          <a href="/" class="footer-link">首页</a>
          <span class="footer-divider">|</span>
          <a href="/user/login" class="footer-link">登录</a>
        </div>
      </div>
      <p class="copyright">
        © 2026 AI 无代码生成应用平台 · 让创意触手可及
      </p>
    </div>
  </a-layout-footer>
</template>

<script setup lang="ts">
// 无需额外的响应式数据
</script>

<style scoped>
.footer {
  background: linear-gradient(180deg, #fafafa 0%, #fff 100%);
  padding: 32px 24px 24px;
  margin-top: auto;
  border-top: 1px solid #f0f0f0;
}

.footer-content {
  max-width: 1200px;
  margin: 0 auto;
  text-align: center;
}

.footer-main {
  display: flex;
  justify-content: center;
  align-items: center;
  gap: 24px;
  margin-bottom: 16px;
}

.footer-brand {
  display: flex;
  align-items: center;
  gap: 8px;
}

.footer-logo {
  width: 24px;
  height: 24px;
  border-radius: 4px;
}

.footer-title {
  font-size: 14px;
  font-weight: 500;
  color: #333;
}

.footer-links {
  display: flex;
  align-items: center;
  gap: 12px;
}

.footer-link {
  font-size: 13px;
  color: #666;
  text-decoration: none;
  transition: color 0.3s;
}

.footer-link:hover {
  color: #1890ff;
}

.footer-divider {
  color: #d9d9d9;
}

.copyright {
  margin: 0;
  font-size: 12px;
  color: #999;
}
</style>
