/**
 * Barrage 火力网压测引擎 - 全局压力模式状态
 */

import { reactive } from 'vue'

// 全局压力模式状态（单例）
const stressMode = reactive({ value: false })

/**
 * 切换压力模式
 */
export const toggleStressMode = (): void => {
  stressMode.value = !stressMode.value
  console.log(stressMode.value ? '🔥 已切换到压力测试模式（全局主题）' : '📋 已切换到普通测试模式（全局主题）')
}

/**
 * 设置压力模式
 */
export const setStressMode = (value: boolean): void => {
  stressMode.value = value
}

/**
 * 获取压力模式状态
 */
export const getStressMode = () => stressMode

/**
 * 获取主题颜色类名
 */
export const getThemeClasses = () => {
  return {
    // 主色调
    primary: stressMode.value ? 'orange' : 'sky',
    primaryLight: stressMode.value ? 'orange-400' : 'sky-400',
    primaryDark: stressMode.value ? 'orange-500' : 'sky-500',

    // 渐变色
    gradient: stressMode.value
      ? 'from-orange-500 to-red-500'
      : 'from-sky-500 to-blue-600',

    // 边框色
    border: stressMode.value
      ? 'border-orange-500/30'
      : 'border-sky-500/30',

    // 背景色
    bg: stressMode.value
      ? 'bg-orange-500/20'
      : 'bg-sky-500/20',

    // 文本色
    text: stressMode.value
      ? 'text-orange-400'
      : 'text-sky-400'
  }
}

/**
 * 获取主题内联样式
 */
export const getThemeStyle = () => {
  return stressMode.value
    ? {
        bg: 'rgba(249, 115, 22, 0.2)',      // orange-500/20
        text: 'rgb(251, 146, 60)',          // orange-400
        border: 'rgba(249, 115, 22, 0.5)',  // orange-500/50
        gradient: 'linear-gradient(90deg, #F97316 0%, #DC2626 50%, #F97316 100%)'
      }
    : {
        bg: 'rgba(14, 165, 233, 0.2)',      // sky-500/20
        text: 'rgb(56, 189, 248)',          // sky-400
        border: 'rgba(14, 165, 233, 0.5)',  // sky-500/50
        gradient: 'linear-gradient(90deg, #0EA5E9 0%, #3B82F6 50%, #0EA5E9 100%)'
      }
}
