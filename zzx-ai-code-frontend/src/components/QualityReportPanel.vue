<template>
  <section v-if="task" class="quality-panel" aria-label="质量检查报告">
    <div class="quality-heading" aria-live="polite">
      <strong>质量检查</strong>
      <a-tag
        :color="
          task.status === 'SUCCEEDED'
            ? 'success'
            : task.status === 'RUNNING'
              ? 'processing'
              : 'error'
        "
      >
        {{ statusLabels[task.status] }}
      </a-tag>
      <span>修复 {{ task.repairAttempts }} / {{ task.maxRepairAttempts }}</span>
      <a-button type="link" size="small" @click="$emit('refresh')">刷新报告</a-button>
    </div>
    <p class="quality-message" role="status">{{ task.message }}</p>
    <a-collapse v-if="task.reports.length" ghost>
      <a-collapse-panel
        v-for="round in task.reports"
        :key="round.checkRound"
        :header="`第 ${round.checkRound + 1} 轮检查`"
      >
        <div v-for="item in round.items" :key="item.name" class="check-item">
          <strong>{{ item.name === 'browser' ? '页面运行' : '工程构建' }}</strong>
          <a-tag :color="item.status === 'PASSED' ? 'success' : 'error'">{{
            checkLabels[item.status] || item.status
          }}</a-tag>
          <span>{{ (Number(item.durationMillis) / 1000).toFixed(1) }} 秒</span>
          <div v-for="(problem, index) in item.problems" :key="index" class="problem">
            <p v-if="problem.file">
              <code>{{ problem.file }}{{ problem.line ? `:${problem.line}` : '' }}</code>
            </p>
            <pre>{{ problem.summary }}</pre>
            <a-button
              v-if="problem.logRef?.startsWith('quality-')"
              type="link"
              size="small"
              @click="openEvidence(problem.logRef)"
              >查看证据</a-button
            >
            <span v-if="problem.category === 'ENVIRONMENT'">需要处理检查环境</span>
          </div>
        </div>
        <div v-if="task.repairs[round.checkRound]" class="repair-record">
          <strong>本轮修复记录</strong>
          <p>{{ task.repairs[round.checkRound]?.changedFiles.join('、') || '未产生有效变更' }}</p>
          <details>
            <summary>变更审计</summary>
            <pre>{{ task.repairs[round.checkRound]?.summary }}</pre>
          </details>
        </div>
      </a-collapse-panel>
    </a-collapse>
    <a-alert
      v-if="task.status !== 'RUNNING' && task.status !== 'SUCCEEDED'"
      type="warning"
      show-icon
      message="本轮未发布，预览保留上次成功结果。"
    />
  </section>
</template>

<script setup lang="ts">
import { message } from 'ant-design-vue'
import { getQualityEvidence, type QualityTask } from '@/api/qualityController'
const props = defineProps<{ task?: QualityTask }>()
defineEmits<{ refresh: [] }>()
const statusLabels = {
  RUNNING: '进行中',
  SUCCEEDED: '已通过',
  FAILED: '未通过',
  CANCELLED: '已取消',
  INTERRUPTED: '已中断',
}
const checkLabels: Record<string, string> = {
  PASSED: '通过',
  FAILED: '失败',
  ERROR: '环境异常',
  TIMEOUT: '超时',
  SKIPPED: '未执行',
}

/** 证据只由鉴权接口获取；失败响应不作为文件展示，临时对象 URL 用后释放。 */
const openEvidence = async (ref: string) => {
  if (!props.task) return
  try {
    const res = await getQualityEvidence(String(props.task.appId), props.task.taskId, ref)
    if (res.data.type.includes('json')) throw new Error('证据读取失败')
    const url = URL.createObjectURL(res.data)
    window.open(url, '_blank', 'noopener,noreferrer')
    setTimeout(() => URL.revokeObjectURL(url), 60000)
  } catch {
    message.error('证据暂不可用，请稍后重试')
  }
}
</script>

<style scoped>
.quality-panel {
  flex: 0 0 auto;
  max-height: 38vh;
  overflow: auto;
  margin: 10px 12px;
  padding: 12px;
  border: 1px solid var(--color-border);
  border-radius: 6px;
  background: var(--color-page);
  font-size: 12px;
}
.quality-heading {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
}
.quality-message {
  margin: 8px 0 0;
  overflow-wrap: anywhere;
}
.check-item {
  margin-bottom: 12px;
}
.check-item > strong {
  margin-right: 8px;
}
.problem pre,
.repair-record pre {
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  max-height: 200px;
  overflow: auto;
  font-size: 11px;
}
.repair-record p {
  overflow-wrap: anywhere;
}
</style>
