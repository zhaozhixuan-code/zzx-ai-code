import { computed, onMounted, onUnmounted, ref, watch, type Ref } from 'vue'

/**
 * 在输入为空时轮播需求示例；输入后停止，卸载时清理计时器与系统偏好监听。
 * @param input 真实输入内容，示例动画不会直接写入它。
 * @param examples 非空示例句子列表；空列表时返回空提示且不启动动画。
 * @returns 当前完整示例、逐字提示、暂停状态以及暂停切换方法。
 */
export function useTypewriterSuggestion(input: Ref<string>, examples: readonly string[]) {
  const index = ref(0)
  const typedText = ref('')
  const paused = ref(false)
  const reducedMotion = ref(false)
  const currentExample = computed(() => examples[index.value] ?? '')
  let timer: ReturnType<typeof setTimeout> | undefined
  let media: MediaQueryList | undefined
  let deleting = false
  let mounted = false

  /** 停止尚未触发的逐字动画计时器，供暂停、输入及卸载分支共用。 */
  function stop(): void {
    if (timer !== undefined) clearTimeout(timer)
    timer = undefined
  }

  /** 推进一个字或切换示例；完整句子会停留片刻，给用户留出 Tab 补全时间。 */
  function tick(): void {
    if (!mounted || input.value || paused.value || reducedMotion.value || !currentExample.value) {
      return
    }
    const full = currentExample.value
    let delay = deleting ? 35 : 105
    if (!deleting && typedText.value.length < full.length) {
      typedText.value = full.slice(0, typedText.value.length + 1)
    } else if (!deleting) {
      deleting = true
      delay = 2600
    } else if (typedText.value.length > 0) {
      typedText.value = typedText.value.slice(0, -1)
    } else {
      deleting = false
      index.value = (index.value + 1) % examples.length
      delay = 400
    }
    timer = setTimeout(tick, delay)
  }

  /** 根据输入、暂停和减少动态效果偏好，同步静态提示或重新安排逐字动画。 */
  function sync(): void {
    stop()
    if (!mounted || input.value) return
    if (paused.value || reducedMotion.value) {
      typedText.value = currentExample.value
      deleting = false
      return
    }
    timer = setTimeout(tick, 500)
  }

  /** 读取系统的减少动态效果偏好，并立即更新动画状态。 */
  function syncMotionPreference(): void {
    reducedMotion.value = media?.matches ?? false
    sync()
  }

  /** 在静态完整示例与逐字轮播之间切换，不改变用户的输入内容。 */
  function togglePaused(): void {
    paused.value = !paused.value
  }

  watch([input, paused], sync)
  onMounted(() => {
    mounted = true
    media = window.matchMedia('(prefers-reduced-motion: reduce)')
    media.addEventListener('change', syncMotionPreference)
    syncMotionPreference()
  })
  onUnmounted(() => {
    mounted = false
    stop()
    media?.removeEventListener('change', syncMotionPreference)
  })

  return { currentExample, typedText, paused, reducedMotion, togglePaused }
}
