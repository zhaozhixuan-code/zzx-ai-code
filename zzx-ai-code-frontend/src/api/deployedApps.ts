import request from '@/request'

/**
 * 获取公开的已部署项目分页，不携带精选或用户筛选条件。
 * @param pageNum 当前页号，从 1 开始。
 * @param pageSize 每页数量，范围为 1 至 20。
 * @returns 包含项目记录、总数及业务状态的接口响应。
 */
export function listDeployedApps(pageNum: number, pageSize: number) {
  return request<API.BaseResponsePageAppVO>('/app/deployed/list/page/vo', {
    method: 'POST',
    data: { pageNum, pageSize },
  })
}
