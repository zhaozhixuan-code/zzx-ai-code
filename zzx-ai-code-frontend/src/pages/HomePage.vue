<template>
  <div id="homePage">
    <!-- 背景装饰 -->
    <div class="bg-decoration">
      <div class="bg-orb bg-orb-1"></div>
      <div class="bg-orb bg-orb-2"></div>
      <div class="bg-orb bg-orb-3"></div>
      <div class="bg-orb bg-orb-4"></div>
      <div class="bg-grid"></div>
      <div class="bg-noise"></div>
    </div>

    <div class="container">
      <!-- 英雄区域 -->
      <section class="hero-section">
        <div class="hero-badge">
          <BulbIcon class="badge-icon" />
          <span>AI 驱动的智能应用生成平台</span>
        </div>
        <h1 class="hero-title">
          一句话轻松创建网站应用
        </h1>
        <p class="hero-description">
          告别复杂的编码过程，只需描述你的想法，AI 就能为你生成完整的网站应用
        </p>
      </section>

      <!-- 输入区域 -->
      <section class="input-section" :class="{ 'is-focused': isFocused }">
        <div class="input-wrapper">
          <div class="input-icon">
            <BulbIcon />
          </div>
          <a-textarea
            v-model:value="userPrompt"
            placeholder="描述你想要的应用，例如：帮我创建一个个人博客网站，包含文章列表、标签分类、评论功能..."
            :rows="3"
            :maxlength="1000"
            :auto-size="{ minRows: 3, maxRows: 8 }"
            class="prompt-input"
            @focus="isFocused = true"
            @blur="isFocused = false"
            @pressEnter="createApp"
          />
          <a-button
            type="primary"
            size="large"
            class="submit-btn"
            @click="createApp"
            :loading="creating"
          >
            <template #icon>
              <RocketIcon />
            </template>
            <span class="btn-text">{{ creating ? '创建中...' : '生成应用' }}</span>
          </a-button>
        </div>
        <div class="input-hint">
          <span>按 Enter 键快速创建</span>
        </div>
      </section>

      <!-- 快捷模板 -->
      <section class="quick-section">
        <div class="section-header">
          <h3 class="section-title">快速开始</h3>
          <p class="section-desc">选择模板，快速创建你的应用</p>
        </div>
        <div class="quick-cards">
          <div
            v-for="(item, index) in quickPrompts"
            :key="index"
            class="quick-card"
            @click="setPrompt(item.prompt)"
          >
            <component :is="item.icon" class="quick-icon" />
            <span class="quick-label">{{ item.label }}</span>
            <ArrowRight class="quick-arrow" />
          </div>
        </div>
      </section>

      <!-- 我的作品 -->
      <section class="section">
        <div class="section-header">
          <h2 class="section-title">
            <span class="title-icon">📁</span>
            我的作品
          </h2>
          <p class="section-desc" v-if="isLoggedIn">管理你创建的应用</p>
          <p class="section-desc" v-else>登录后查看你创建的应用</p>
        </div>

        <!-- 未登录状态 -->
        <div v-if="!isLoggedIn" class="empty-state login-prompt">
          <div class="empty-icon-wrapper">
            <span class="empty-icon">🔐</span>
          </div>
          <h3 class="empty-title">登录后查看你的作品</h3>
          <p class="empty-desc">登录后可创建和管理你自己的 AI 应用</p>
          <a-button type="primary" size="large" @click="goToLogin" class="login-btn">
            立即登录
                        <ArrowRight />
          </a-button>
        </div>

        <!-- 已登录但无应用 -->
        <div v-else-if="myAppsEmpty" class="empty-state">
          <div class="empty-icon-wrapper">
            <span class="empty-icon">🚀</span>
          </div>
          <h3 class="empty-title">还没有创建任何应用</h3>
          <p class="empty-desc">在上方输入你的想法，让 AI 为你创建第一个应用</p>
        </div>

        <!-- 应用列表 -->
        <div v-else class="app-grid">
          <AppCard
            v-for="app in myApps"
            :key="app.id"
            :app="app"
            @view-chat="viewChat"
            @view-work="viewWork"
          />
        </div>

        <!-- 分页 -->
        <div v-if="!myAppsEmpty && isLoggedIn" class="pagination-wrapper">
          <a-pagination
            v-model:current="myAppsPage.current"
            v-model:page-size="myAppsPage.pageSize"
            :total="myAppsPage.total"
            :show-size-changer="false"
            :show-total="myAppsTotalText"
            @change="loadMyApps"
          />
        </div>
      </section>

      <!-- 精选案例 -->
      <section class="section">
        <div class="section-header">
          <h2 class="section-title">
            <span class="title-icon">⭐</span>
            精选案例
          </h2>
          <p class="section-desc">灵感启迪，看看其他人创建的优秀应用</p>
        </div>

        <!-- 无精选应用 -->
        <div v-if="featuredAppsEmpty" class="empty-state">
          <div class="empty-icon-wrapper">
            <span class="empty-icon">📦</span>
          </div>
          <h3 class="empty-title">暂无精选案例</h3>
          <p class="empty-desc">精选案例将在这里展示</p>
        </div>

        <!-- 精选列表 -->
        <div v-else class="featured-grid">
          <AppCard
            v-for="app in featuredApps"
            :key="app.id"
            :app="app"
            :featured="true"
            @view-chat="viewChat"
            @view-work="viewWork"
          />
        </div>

        <!-- 分页 -->
        <div v-if="!featuredAppsEmpty" class="pagination-wrapper">
          <a-pagination
            v-model:current="featuredAppsPage.current"
            v-model:page-size="featuredAppsPage.pageSize"
            :total="featuredAppsPage.total"
            :show-size-changer="false"
            :show-total="featuredAppsTotalText"
            @change="loadFeaturedApps"
          />
        </div>
      </section>
    </div>
  </div>
</template>

<style scoped>
/* ===== 基础变量 ===== */
:root {
  --primary-color: #3b82f6;
  --primary-hover: #2563eb;
  --success-color: #10b981;
  --text-primary: #1e293b;
  --text-secondary: #64748b;
  --text-muted: #94a3b8;
  --bg-card: rgba(255, 255, 255, 0.9);
  --border-light: rgba(148, 163, 184, 0.2);
  --shadow-sm: 0 1px 3px rgba(0, 0, 0, 0.05);
  --shadow-md: 0 4px 12px rgba(0, 0, 0, 0.08);
  --shadow-lg: 0 10px 40px rgba(0, 0, 0, 0.12);
  --shadow-xl: 0 20px 60px rgba(0, 0, 0, 0.15);
  --radius-sm: 8px;
  --radius-md: 12px;
  --radius-lg: 16px;
  --radius-xl: 24px;
}

#homePage {
  width: 100%;
  min-height: 100vh;
  background:
    radial-gradient(ellipse 120% 100% at 50% 0%, rgba(99, 102, 241, 0.12) 0%, rgba(99, 102, 241, 0.03) 50%, transparent 70%),
    radial-gradient(ellipse 100% 80% at 100% 100%, rgba(139, 92, 246, 0.1) 0%, transparent 50%),
    radial-gradient(ellipse 80% 60% at 0% 80%, rgba(16, 185, 129, 0.08) 0%, transparent 50%),
    linear-gradient(180deg, #f0f4ff 0%, #e8eef7 30%, #dce5f0 100%);
  position: relative;
  overflow: hidden;
}

/* ===== 背景装饰 ===== */
.bg-decoration {
  position: absolute;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  pointer-events: none;
  overflow: hidden;
  z-index: 0;
}

/* 顶部装饰光晕 */
.bg-decoration::before {
  content: '';
  position: absolute;
  top: -300px;
  left: 50%;
  transform: translateX(-50%);
  width: 800px;
  height: 600px;
  background: radial-gradient(ellipse, rgba(99, 102, 241, 0.12) 0%, rgba(139, 92, 246, 0.08) 30%, transparent 70%);
  filter: blur(40px);
}

/* 底部装饰光晕 */
.bg-decoration::after {
  content: '';
  position: absolute;
  bottom: -200px;
  left: 20%;
  width: 600px;
  height: 400px;
  background: radial-gradient(ellipse, rgba(16, 185, 129, 0.08) 0%, rgba(59, 130, 246, 0.05) 50%, transparent 70%);
  filter: blur(50px);
}

.bg-orb {
  position: absolute;
  border-radius: 50%;
  filter: blur(50px);
  opacity: 0.5;
  animation: float 12s ease-in-out infinite;
}

.bg-orb-1 {
  width: 600px;
  height: 600px;
  background: radial-gradient(circle, rgba(99, 102, 241, 0.35) 0%, rgba(139, 92, 246, 0.2) 50%, transparent 70%);
  top: -200px;
  right: -150px;
  animation-delay: 0s;
}

.bg-orb-2 {
  width: 500px;
  height: 500px;
  background: radial-gradient(circle, rgba(236, 72, 153, 0.25) 0%, rgba(99, 102, 241, 0.15) 50%, transparent 70%);
  bottom: 5%;
  left: -100px;
  animation-delay: -4s;
}

.bg-orb-3 {
  width: 450px;
  height: 450px;
  background: radial-gradient(circle, rgba(16, 185, 129, 0.25) 0%, rgba(59, 130, 246, 0.15) 50%, transparent 70%);
  top: 25%;
  right: 15%;
  animation-delay: -8s;
}

.bg-orb-4 {
  width: 300px;
  height: 300px;
  background: radial-gradient(circle, rgba(245, 158, 11, 0.2) 0%, rgba(239, 68, 68, 0.1) 50%, transparent 70%);
  top: 55%;
  left: 35%;
  animation-delay: -6s;
}

/* 网格背景 */
.bg-grid {
  position: absolute;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  background-image:
    linear-gradient(rgba(99, 102, 241, 0.04) 1px, transparent 1px),
    linear-gradient(90deg, rgba(99, 102, 241, 0.04) 1px, transparent 1px);
  background-size: 50px 50px;
  animation: gridScroll 40s linear infinite;
  opacity: 0.8;
}

/* 噪点纹理 */
.bg-noise {
  position: absolute;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  opacity: 0.03;
  background-image: url("data:image/svg+xml,%3Csvg viewBox='0 0 256 256' xmlns='http://www.w3.org/2000/svg'%3E%3Cfilter id='noise'%3E%3CfeTurbulence type='fractalNoise' baseFrequency='0.9' numOctaves='4' stitchTiles='stitch'/%3E%3C/filter%3E%3Crect width='100%25' height='100%25' filter='url(%23noise)'/%3E%3C/svg%3E");
  pointer-events: none;
}

@keyframes float {
  0%, 100% {
    transform: translate(0, 0) scale(1);
  }
  33% {
    transform: translate(30px, -30px) scale(1.05);
  }
  66% {
    transform: translate(-20px, 20px) scale(0.95);
  }
}

@keyframes gridScroll {
  0% {
    transform: translate(0, 0);
  }
  100% {
    transform: translate(60px, 60px);
  }
}

/* ===== 容器 ===== */
.container {
  max-width: 1100px;
  margin: 0 auto;
  padding: 40px 24px 80px;
  position: relative;
  z-index: 1;
}

/* ===== 英雄区域 ===== */
.hero-section {
  text-align: center;
  padding: 60px 0 50px;
}

.hero-badge {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 8px 16px;
  background: linear-gradient(135deg, rgba(59, 130, 246, 0.1), rgba(139, 92, 246, 0.1));
  border: 1px solid rgba(59, 130, 246, 0.2);
  border-radius: 50px;
  font-size: 14px;
  color: #3b82f6;
  margin-bottom: 24px;
  animation: fadeInDown 0.6s ease-out;
}

.badge-icon {
  font-size: 14px;
}

.hero-title {
  font-size: 52px;
  font-weight: 800;
  margin: 0 0 20px;
  line-height: 1.2;
  background: linear-gradient(135deg, #3b82f6 0%, #8b5cf6 50%, #ec4899 100%);
  -webkit-background-clip: text;
  -webkit-text-fill-color: transparent;
  background-clip: text;
  letter-spacing: -2px;
  animation: shimmer 3s ease-in-out infinite;
  background-size: 200% 200%;
}

.hero-description {
  font-size: 18px;
  color: #64748b;
  max-width: 540px;
  margin: 0 auto;
  line-height: 1.6;
}

@keyframes fadeInDown {
  from {
    opacity: 0;
    transform: translateY(-20px);
  }
  to {
    opacity: 1;
    transform: translateY(0);
  }
}

@keyframes shimmer {
  0%, 100% {
    background-position: 0% 50%;
  }
  50% {
    background-position: 100% 50%;
  }
}

/* ===== 输入区域 ===== */
.input-section {
  max-width: 800px;
  margin: 0 auto 50px;
  animation: fadeInUp 0.6s ease-out 0.2s both;
}

@keyframes fadeInUp {
  from {
    opacity: 0;
    transform: translateY(20px);
  }
  to {
    opacity: 1;
    transform: translateY(0);
  }
}

.input-wrapper {
  position: relative;
  background: rgba(255, 255, 255, 0.95);
  border-radius: 20px;
  box-shadow: 0 4px 24px rgba(0, 0, 0, 0.08), 0 0 0 1px rgba(0, 0, 0, 0.04);
  padding: 8px;
  transition: all 0.3s ease;
}

.input-section.is-focused .input-wrapper {
  box-shadow: 0 8px 32px rgba(59, 130, 246, 0.15), 0 0 0 2px rgba(59, 130, 246, 0.3);
  transform: translateY(-2px);
}

.input-icon {
  position: absolute;
  left: 20px;
  top: 50%;
  transform: translateY(-50%);
  color: #94a3b8;
  font-size: 20px;
  z-index: 1;
}

.prompt-input {
  border: none;
  background: transparent;
  font-size: 15px;
  padding: 16px 120px 16px 52px;
  resize: none;
  border-radius: 16px;
  line-height: 1.6;
}

.prompt-input:focus {
  box-shadow: none;
}

.prompt-input::placeholder {
  color: #94a3b8;
}

.submit-btn {
  position: absolute;
  right: 12px;
  top: 50%;
  transform: translateY(-50%);
  height: 42px;
  padding: 0 20px;
  border-radius: 12px;
  background: linear-gradient(135deg, #3b82f6 0%, #2563eb 100%);
  border: none;
  font-weight: 500;
  box-shadow: 0 4px 12px rgba(59, 130, 246, 0.3);
  transition: all 0.3s ease;
}

.submit-btn:hover {
  background: linear-gradient(135deg, #2563eb 0%, #1d4ed8 100%);
  box-shadow: 0 6px 20px rgba(59, 130, 246, 0.4);
  transform: translateY(-50%) scale(1.02);
}

.btn-text {
  margin-left: 4px;
}

.input-hint {
  text-align: center;
  margin-top: 12px;
  font-size: 13px;
  color: #94a3b8;
}

/* ===== 快捷模板 ===== */
.quick-section {
  max-width: 900px;
  margin: 0 auto 60px;
  animation: fadeInUp 0.6s ease-out 0.3s both;
}

.section-header {
  text-align: center;
  margin-bottom: 28px;
}

.section-title {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 10px;
  font-size: 26px;
  font-weight: 600;
  color: #1e293b;
  margin: 0 0 8px;
}

.title-icon {
  font-size: 24px;
}

.section-desc {
  font-size: 15px;
  color: #64748b;
  margin: 0;
}

.quick-cards {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 16px;
}

.quick-card {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 18px 20px;
  background: rgba(255, 255, 255, 0.85);
  border: 1px solid rgba(148, 163, 184, 0.15);
  border-radius: 14px;
  cursor: pointer;
  transition: all 0.3s ease;
  position: relative;
  overflow: hidden;
}

.quick-card::before {
  content: '';
  position: absolute;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  background: linear-gradient(135deg, rgba(59, 130, 246, 0.05), rgba(139, 92, 246, 0.05));
  opacity: 0;
  transition: opacity 0.3s ease;
}

.quick-card:hover {
  border-color: rgba(59, 130, 246, 0.3);
  transform: translateY(-4px);
  box-shadow: 0 12px 32px rgba(59, 130, 246, 0.15);
}

.quick-card:hover::before {
  opacity: 1;
}

.quick-icon {
  font-size: 24px;
  flex-shrink: 0;
}

.quick-label {
  font-size: 15px;
  font-weight: 500;
  color: #334155;
  flex: 1;
}

.quick-arrow {
  font-size: 14px;
  color: #94a3b8;
  transition: all 0.3s ease;
}

.quick-card:hover .quick-arrow {
  color: #3b82f6;
  transform: translateX(4px);
}

/* ===== 通用区块 ===== */
.section {
  margin-bottom: 70px;
}

.section .section-header {
  text-align: left;
  margin-bottom: 32px;
}

.section .section-title {
  justify-content: flex-start;
  font-size: 28px;
}

/* ===== 空状态 ===== */
.empty-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 60px 20px;
  background: rgba(255, 255, 255, 0.6);
  border: 2px dashed rgba(148, 163, 184, 0.3);
  border-radius: 20px;
  text-align: center;
}

.empty-icon-wrapper {
  width: 80px;
  height: 80px;
  display: flex;
  align-items: center;
  justify-content: center;
  background: linear-gradient(135deg, rgba(59, 130, 246, 0.1), rgba(139, 92, 246, 0.1));
  border-radius: 50%;
  margin-bottom: 20px;
}

.empty-icon {
  font-size: 36px;
}

.empty-title {
  font-size: 20px;
  font-weight: 600;
  color: #334155;
  margin: 0 0 8px;
}

.empty-desc {
  font-size: 15px;
  color: #64748b;
  margin: 0 0 24px;
  max-width: 300px;
}

/* 登录提示特殊样式 */
.login-prompt {
  background: linear-gradient(135deg, rgba(59, 130, 246, 0.05), rgba(139, 92, 246, 0.05));
}

.login-btn {
  height: 44px;
  padding: 0 28px;
  border-radius: 12px;
  background: linear-gradient(135deg, #3b82f6 0%, #2563eb 100%);
  font-weight: 500;
  display: inline-flex;
  align-items: center;
  gap: 8px;
  transition: all 0.3s ease;
}

.login-btn:hover {
  background: linear-gradient(135deg, #2563eb 0%, #1d4ed8 100%);
  transform: translateY(-2px);
  box-shadow: 0 8px 20px rgba(59, 130, 246, 0.3);
}

/* ===== 应用网格 ===== */
.app-grid,
.featured-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 24px;
  margin-bottom: 32px;
}

/* ===== 分页 ===== */
.pagination-wrapper {
  display: flex;
  justify-content: center;
  margin-top: 32px;
}

/* ===== 响应式设计 ===== */
@media (max-width: 992px) {
  .hero-title {
    font-size: 40px;
  }

  .quick-cards {
    grid-template-columns: repeat(2, 1fr);
  }

  .app-grid,
  .featured-grid {
    grid-template-columns: repeat(2, 1fr);
  }
}

@media (max-width: 768px) {
  .container {
    padding: 24px 16px 60px;
  }

  .hero-section {
    padding: 40px 0 30px;
  }

  .hero-title {
    font-size: 28px;
  }

  .hero-description {
    font-size: 15px;
  }

  .input-wrapper {
    padding: 6px;
  }

  .prompt-input {
    padding: 14px 16px 14px 44px;
    font-size: 14px;
  }

  .input-icon {
    left: 14px;
    font-size: 18px;
  }

  .submit-btn {
    position: relative;
    top: auto;
    right: auto;
    transform: none;
    width: 100%;
    margin-top: 12px;
    height: 46px;
  }

  .submit-btn:hover {
    transform: none;
  }

  .quick-cards {
    grid-template-columns: 1fr;
    gap: 12px;
  }

  .quick-card {
    padding: 14px 16px;
  }

  .section .section-title {
    font-size: 22px;
  }

  .app-grid,
  .featured-grid {
    grid-template-columns: 1fr;
    gap: 16px;
  }

  .empty-state {
    padding: 40px 20px;
  }

  .empty-icon-wrapper {
    width: 64px;
    height: 64px;
  }

  .empty-icon {
    font-size: 28px;
  }

  .empty-title {
    font-size: 18px;
  }
}
</style>

<script setup lang="ts">
import { ref, reactive, onMounted, computed } from 'vue'
import { useRouter } from 'vue-router'
import { message } from 'ant-design-vue'
import { useLoginUserStore } from '@/stores/loginUser'
import { addApp, listMyAppVoByPage, listGoodAppVoByPage } from '@/api/appController'
import { getDeployUrl } from '@/config/env'
import AppCard from '@/components/AppCard.vue'
import {
  BulbOutlined,
  RocketOutlined,
  RightOutlined,
  FileTextOutlined,
  BankOutlined,
  PictureOutlined,
  LockOutlined,
  InboxOutlined,
  StarOutlined,
  FolderOutlined
} from '@ant-design/icons-vue'

const BulbIcon = BulbOutlined
const RocketIcon = RocketOutlined
const ArrowRight = RightOutlined
const FileTextIcon = FileTextOutlined
const BankIcon = BankOutlined
const PictureIcon = PictureOutlined
const LockIcon = LockOutlined
const InboxIcon = InboxOutlined
const StarIcon = StarOutlined
const FolderIcon = FolderOutlined

const router = useRouter()
const loginUserStore = useLoginUserStore()

// 用户提示词
const userPrompt = ref('')
const creating = ref(false)
const isFocused = ref(false)

// 我的应用数据
const myApps = ref<API.AppVO[]>([])
const myAppsPage = reactive({
  current: 1,
  pageSize: 6,
  total: 0,
})

// 精选应用数据
const featuredApps = ref<API.AppVO[]>([])
const featuredAppsPage = reactive({
  current: 1,
  pageSize: 6,
  total: 0,
})

// 快捷提示词
const quickPrompts = [
  {
    label: '个人博客',
    icon: FileTextIcon,
    prompt: '创建一个现代化的个人博客网站，包含文章列表、详情页、分类标签、搜索功能、评论系统和个人简介页面。不超过300行代码'
  },
  {
    label: '企业官网',
    icon: BankIcon,
    prompt: '设计一个专业的企业官网，包含公司介绍、产品服务展示、新闻资讯、联系我们等页面。'
  },
  {
    label: '待办事项',
    icon: FolderOutlined,
    prompt: '创建一个简洁的待办事项应用，包含任务添加、编辑、删除、完成标记等功能。'
  },
  {
    label: '作品展示',
    icon: PictureIcon,
    prompt: '制作一个精美的作品展示网站，适合设计师、摄影师、艺术家等创作者。包含作品画廊、项目详情页、个人简历、联系方式等模块。'
  }
]

// 是否已登录
const isLoggedIn = computed(() => !!loginUserStore.loginUser.id)

// 我的应用是否为空
const myAppsEmpty = computed(() => myApps.value.length === 0 && myAppsPage.total === 0)

// 精选应用是否为空
const featuredAppsEmpty = computed(() => featuredApps.value.length === 0 && featuredAppsPage.total === 0)

// 分页显示文本
const myAppsTotalText = (total: number) => `共 ${total} 个应用`
const featuredAppsTotalText = (total: number) => `共 ${total} 个案例`

// 设置提示词
const setPrompt = (prompt: string) => {
  userPrompt.value = prompt
  // 聚焦到输入框
  const textarea = document.querySelector('.prompt-input textarea') as HTMLTextAreaElement
  if (textarea) {
    textarea.focus()
  }
}

// 创建应用
const createApp = async () => {
  if (!userPrompt.value.trim()) {
    message.warning('请输入应用描述')
    return
  }

  if (!loginUserStore.loginUser.id) {
    message.warning('请先登录')
    loginUserStore.openLoginModal()
    return
  }

  creating.value = true
  try {
    const res = await addApp({
      initPrompt: userPrompt.value.trim(),
    })

    if (res.data.code === 0 && res.data.data) {
      message.success('应用创建成功')
      const appId = String(res.data.data)
      await router.push(`/app/chat/${appId}`)
    } else {
      message.error('创建失败：' + res.data.message)
    }
  } catch (error) {
    console.error('创建应用失败：', error)
    message.error('创建失败，请重试')
  } finally {
    creating.value = false
  }
}

// 加载我的应用
const loadMyApps = async () => {
  if (!loginUserStore.loginUser.id) {
    return
  }

  try {
    const res = await listMyAppVoByPage({
      pageNum: myAppsPage.current,
      pageSize: myAppsPage.pageSize,
      sortField: 'createTime',
      sortOrder: 'desc',
    })

    if (res.data.code === 0 && res.data.data) {
      myApps.value = res.data.data.records || []
      myAppsPage.total = res.data.data.totalRow || 0
    }
  } catch (error) {
    console.error('加载我的应用失败：', error)
  }
}

// 加载精选应用
const loadFeaturedApps = async () => {
  try {
    const res = await listGoodAppVoByPage({
      pageNum: featuredAppsPage.current,
      pageSize: featuredAppsPage.pageSize,
      sortField: 'createTime',
      sortOrder: 'desc',
    })

    if (res.data.code === 0 && res.data.data) {
      featuredApps.value = res.data.data.records || []
      featuredAppsPage.total = res.data.data.totalRow || 0
    }
  } catch (error) {
    console.error('加载精选应用失败：', error)
  }
}

// 查看对话
const viewChat = (appId: string | number | undefined) => {
  if (appId) {
    router.push(`/app/chat/${appId}?view=1`)
  }
}

// 查看作品
const viewWork = (app: API.AppVO) => {
  if (app.deployKey) {
    const url = getDeployUrl(app.deployKey)
    window.open(url, '_blank')
  }
}

// 跳转到登录（使用弹窗）
const goToLogin = () => {
  loginUserStore.openLoginModal()
}

// 页面加载时获取数据
onMounted(() => {
  loadMyApps()
  loadFeaturedApps()

  // 鼠标跟随光效
  const handleMouseMove = (e: MouseEvent) => {
    const { clientX, clientY } = e
    const { innerWidth, innerHeight } = window
    const x = (clientX / innerWidth) * 100
    const y = (clientY / innerHeight) * 100

    document.documentElement.style.setProperty('--mouse-x', `${x}%`)
    document.documentElement.style.setProperty('--mouse-y', `${y}%`)
  }

  document.addEventListener('mousemove', handleMouseMove)

  return () => {
    document.removeEventListener('mousemove', handleMouseMove)
  }
})
</script>
