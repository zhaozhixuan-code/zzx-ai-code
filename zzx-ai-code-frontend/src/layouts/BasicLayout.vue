<template>
  <a-layout class="basic-layout">
    <WorkspaceSidebar />
    <a-layout class="main-shell">
      <a-layout-content class="main-content">
        <router-view />
      </a-layout-content>
      <GlobalFooter v-if="!isFullScreenWorkspace" />
    </a-layout>
  </a-layout>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import WorkspaceSidebar from '@/components/WorkspaceSidebar.vue'
import GlobalFooter from '@/components/GlobalFooter.vue'

const route = useRoute()
const isFullScreenWorkspace = computed(() => route.meta.fullScreen === true)
</script>

<style scoped>
.basic-layout {
  display: flex;
  flex-direction: row;
  min-height: 100vh;
  background: #fff;
}

.main-shell {
  min-width: 0;
  min-height: 100vh;
  flex: 1;
  flex-direction: column;
  background: #fff;
}

.main-content {
  min-width: 0;
  min-height: 0;
  flex: 1;
  width: 100%;
  padding: 0;
  background: #fff;
  margin: 0;
}

@media (max-width: 760px) {
  .basic-layout {
    flex-direction: column;
  }

  .main-shell {
    min-height: 0;
  }
}
</style>
