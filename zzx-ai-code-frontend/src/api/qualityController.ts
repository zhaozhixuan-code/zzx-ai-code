import request from '@/request'

export type QualityStatus = 'RUNNING' | 'SUCCEEDED' | 'FAILED' | 'CANCELLED' | 'INTERRUPTED'
export interface QualityProblem {
  category: string
  repairable: boolean
  file?: string
  line?: number
  column?: number
  summary: string
  logRef?: string
}
export interface QualityReport {
  checkRound: number
  startedAt: string
  finishedAt?: string
  items: {
    name: string
    required: boolean
    status: string
    durationMillis: number | string
    problems: QualityProblem[]
  }[]
  artifactId?: string
}
export interface QualityTask {
  taskId: string
  appId: number | string
  status: QualityStatus
  stage: string
  repairAttempts: number
  maxRepairAttempts: number
  startedAt: string
  finishedAt?: string
  message: string
  reports: QualityReport[]
  repairs: { attempt: number; changed: boolean; changedFiles: string[]; summary: string }[]
}

/** 查询已鉴权应用的最新报告；taskId 非空时读取指定历史任务。 */
export const getQualityReport = (appId: string, taskId?: string) =>
  request<{ code: number; data?: QualityTask; message?: string }>('/app/quality/report', {
    method: 'GET',
    params: { appId, taskId },
  })

/** 通过统一请求客户端获取有归属保护的日志或截图。 */
export const getQualityEvidence = (appId: string, taskId: string, ref: string) =>
  request<Blob>('/app/quality/evidence', {
    method: 'GET',
    params: { appId, taskId, ref },
    responseType: 'blob',
  })
