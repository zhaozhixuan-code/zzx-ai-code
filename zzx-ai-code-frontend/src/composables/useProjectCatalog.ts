import { computed, onUnmounted, ref, watch, type ComputedRef } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { isAxiosError } from 'axios'

type ProjectPageLoader = (
  pageNum: number,
  pageSize: number,
) => Promise<{ data: API.BaseResponsePageAppVO }>

/**
 * 管理项目卡片列表的 URL 分页、加载与失败状态，忽略过期请求和账户切换前的响应。
 * @param fetchPage 分页加载函数，返回项目列表接口响应。
 * @param scope 当前数据所属范围；空字符串表示未登录且不发送请求，默认公开列表。
 * @returns 当前页、项目记录、总数、加载/错误状态和刷新、翻页方法。
 */
export function useProjectCatalog(
  fetchPage: ProjectPageLoader,
  scope: ComputedRef<string> = computed(() => 'public'),
) {
  const route = useRoute()
  const router = useRouter()
  const projects = ref<API.AppVO[]>([])
  const total = ref(0)
  const loading = ref(false)
  const error = ref('')
  const pageSize = 12
  const currentPage = computed(() => {
    const page = Number(route.query.page)
    return Number.isSafeInteger(page) && page > 0 ? page : 1
  })
  let requestVersion = 0

  /**
   * 将页号写入 URL，保留其他查询参数并支持浏览器前进后退。
   * @param page 目标页号，从 1 开始。
   * @returns 路由导航完成后的 Promise。
   */
  async function changePage(page: number): Promise<void> {
    await router.push({ query: { ...route.query, page: page === 1 ? undefined : String(page) } })
  }

  /** 读取当前页；业务或网络失败时保留明确的重试状态，不将失败伪装为空列表。 */
  async function reload(): Promise<void> {
    const version = ++requestVersion
    projects.value = []
    total.value = 0
    error.value = ''
    loading.value = false
    if (!scope.value) return

    loading.value = true
    try {
      const response = await fetchPage(currentPage.value, pageSize)
      if (version !== requestVersion) return
      if (response.data.code !== 0 || !response.data.data) {
        throw new Error(response.data.message || '项目暂时无法加载，请重试')
      }
      total.value = response.data.data.totalRow ?? 0
      const lastPage = Math.max(1, Math.ceil(total.value / pageSize))
      // 删除项目或旧书签可能使当前页越界，回到仍然存在的最后一页。
      if (currentPage.value > lastPage) {
        await router.replace({
          query: { ...route.query, page: lastPage === 1 ? undefined : String(lastPage) },
        })
        return
      }
      projects.value = response.data.data.records ?? []
    } catch (cause) {
      if (version !== requestVersion) return
      error.value = isAxiosError(cause)
        ? '暂时无法连接项目列表，请稍后重试。'
        : cause instanceof Error
          ? cause.message
          : '项目暂时无法加载，请重试'
    } finally {
      if (version === requestVersion) loading.value = false
    }
  }

  watch([currentPage, scope], reload, { immediate: true })
  onUnmounted(() => {
    requestVersion++
  })

  return { projects, total, currentPage, pageSize, loading, error, reload, changePage }
}
