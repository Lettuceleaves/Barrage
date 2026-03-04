<template>
  <canvas ref="canvasRef" class="matrix-rain-canvas"></canvas>
</template>

<script setup lang="ts">
import { ref, onMounted, onUnmounted } from 'vue'

interface Props {
  text?: string
  fontSize?: number
  colors?: string[]
  speed?: number
}

const props = withDefaults(defineProps<Props>(), {
  text: 'LeetCode LeetCode LeetCode LeetCode LeetCode LeetCode LeetCode',
  fontSize: 10,
  colors: () => ['#05FF00', '#00BFFF', '#FF4500', '#FFA500', '#C202C2'],
  speed: 30
})

const canvasRef = ref<HTMLCanvasElement | null>(null)
let animationTimer: number | null = null
let ctx: CanvasRenderingContext2D | null = null
let drops: number[] = []
let letters: string[] = []
let colorIndex = 0

const initCanvas = () => {
  if (!canvasRef.value) return

  const canvas = canvasRef.value
  ctx = canvas.getContext('2d')
  if (!ctx) return

  // 设置画布尺寸
  canvas.width = window.innerWidth
  canvas.height = window.innerHeight

  // 处理字母
  letters = props.text.split('')

  // 计算列数
  const columns = Math.floor(canvas.width / props.fontSize)

  // 初始化下落位置
  drops = []
  for (let i = 0; i < columns; i++) {
    drops[i] = 1
  }

  // 随机选择颜色
  colorIndex = Math.floor(Math.random() * props.colors.length)

  // 启动动画
  startAnimation()
}

const draw = () => {
  if (!ctx || !canvasRef.value) return

  // 绘制半透明黑色背景，产生拖尾效果
  ctx.fillStyle = 'rgba(0, 0, 0, .1)'
  ctx.fillRect(0, 0, canvasRef.value.width, canvasRef.value.height)

  // 绘制字母
  ctx.fillStyle = props.colors[colorIndex]
  ctx.font = `${props.fontSize}px monospace`

  for (let i = 0; i < drops.length; i++) {
    const text = letters[Math.floor(Math.random() * letters.length)]
    ctx.fillText(text, i * props.fontSize, drops[i] * props.fontSize)

    drops[i]++

    // 重置下落位置
    if (drops[i] * props.fontSize > canvasRef.value.height && Math.random() > 0.95) {
      drops[i] = 0
    }
  }
}

const startAnimation = () => {
  if (animationTimer) {
    clearInterval(animationTimer)
  }
  animationTimer = window.setInterval(draw, props.speed)
}

const handleResize = () => {
  if (canvasRef.value && ctx) {
    canvasRef.value.width = window.innerWidth
    canvasRef.value.height = window.innerHeight

    // 重新计算列数
    const columns = Math.floor(canvasRef.value.width / props.fontSize)
    drops = []
    for (let i = 0; i < columns; i++) {
      drops[i] = Math.floor(Math.random() * columns)
    }
  }
}

onMounted(() => {
  initCanvas()
  window.addEventListener('resize', handleResize)
})

onUnmounted(() => {
  if (animationTimer) {
    clearInterval(animationTimer)
  }
  window.removeEventListener('resize', handleResize)
})
</script>

<style scoped>
.matrix-rain-canvas {
  position: fixed;
  left: 0;
  top: 0;
  width: 100vw;
  height: 100vh;
  display: block;
  z-index: -1;
  background-color: #000000;
}
</style>
