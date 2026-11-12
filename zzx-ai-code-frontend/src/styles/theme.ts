import type { ThemeConfig } from 'ant-design-vue/es/config-provider/context'

// 页面、组件库和挂载到 body 的弹窗共用此配色，避免各页面分别维护主色。
const palette = {
  primary: '#236b60',
  primaryHover: '#1b594f',
  primarySoft: '#e6f1ed',
  primarySurface: '#f3f8f6',
  primaryBorder: '#c2d9d1',
  primaryMuted: '#4e776e',
  primaryDisabled: '#c6d9d3',
  page: '#f7f9fa',
  surface: '#ffffff',
  surfaceMuted: '#f1f5f5',
  sidebar: '#edf3f1',
  text: '#263b37',
  textSecondary: '#536860',
  textMuted: '#5d7068',
  placeholder: '#5d7068',
  border: '#dee7e3',
  borderStrong: '#c8d8d1',
  focus: '#8bb7aa',
  success: '#39795b',
  successBg: '#eef7f0',
  warning: '#93652d',
  warningHover: '#7d5525',
  warningBg: '#fbf5e8',
  error: '#ae4f4b',
} as const

const fontFamily = "'Segoe UI', 'PingFang SC', 'Microsoft YaHei', sans-serif"

export const appTheme: ThemeConfig = {
  token: {
    colorPrimary: palette.primary,
    colorPrimaryHover: palette.primaryHover,
    colorPrimaryActive: palette.primaryHover,
    colorPrimaryBg: palette.primarySoft,
    colorPrimaryBgHover: palette.primarySoft,
    colorPrimaryBorder: palette.primaryBorder,
    colorPrimaryBorderHover: palette.focus,
    colorInfo: palette.primary,
    colorSuccess: palette.success,
    colorSuccessBg: palette.successBg,
    colorWarning: palette.warning,
    colorWarningBg: palette.warningBg,
    colorError: palette.error,
    colorLink: palette.primary,
    colorLinkHover: palette.primaryHover,
    colorLinkActive: palette.primaryHover,
    colorText: palette.text,
    colorTextSecondary: palette.textSecondary,
    colorTextTertiary: palette.textMuted,
    colorTextPlaceholder: palette.placeholder,
    colorBgLayout: palette.page,
    colorBgContainer: palette.surface,
    colorBgElevated: palette.surface,
    colorFillAlter: palette.surfaceMuted,
    colorBorder: palette.borderStrong,
    colorBorderSecondary: palette.border,
    controlItemBgActive: palette.primarySoft,
    controlItemBgActiveHover: palette.primarySoft,
    controlOutline: 'rgba(35, 107, 96, 0.12)',
    borderRadius: 8,
    fontFamily,
  },
}

/** 将全站颜色变量注册到根元素，使普通页面、侧栏及传送到 body 的弹窗共享主题。 */
export function applyWorkspaceTheme(): void {
  for (const [name, value] of Object.entries(palette)) {
    const cssName = name.replace(/[A-Z]/g, (letter) => `-${letter.toLowerCase()}`)
    document.documentElement.style.setProperty(`--color-${cssName}`, value)
  }
  document.documentElement.style.setProperty('--font-ui', fontFamily)
  document.documentElement.style.setProperty('--shadow-focus', '0 0 0 3px rgba(35, 107, 96, 0.12)')
  document.documentElement.style.setProperty('--shadow-card', '0 8px 24px rgba(38, 59, 55, 0.06)')
  document.documentElement.style.setProperty(
    '--shadow-composer',
    '0 12px 36px rgba(38, 59, 55, 0.06), 0 2px 6px rgba(38, 59, 55, 0.03)',
  )
}
