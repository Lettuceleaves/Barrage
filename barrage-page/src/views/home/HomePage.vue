<!--
  Barrage 火力网压测引擎 - 首页
-->

<template>
  <div class="relative w-full h-full overflow-hidden bg-zinc-950">
    <!-- ==================== 背景层 ==================== -->

    <!-- 噪点纹理层 -->
    <div
      class="absolute inset-0 opacity-[0.015]"
      style="background-image: url('data:image/svg+xml;base64,PHN2ZyB4bWxucz0iaHR0cDovL3d3dy53My5vcmcvMjAwMC9zdmciIHdpZHRoPSIzMDAiIGhlaWdodD0iMzAwIj48ZmlsdGVyIGlkPSJhIiB4PSIwIiB5PSIwIj48ZmVUdXJidWxlbmNlIGJhc2VGcmVxdWVuY3k9Ii43NSIgc3RpdGNoVGlsZXM9InN0aXRjaCIgdHlwZT0iZnJhY3RhbE5vaXNlIi8+PGZlQ29sb3JNYXRyaXggdHlwZT0ic2F0dXJhdGUiIHZhbHVlcz0iMCIvPjwvZmlsdGVyPjxwYXRoIGQ9Ik0wIDBoMzAwdjMwMEgweiIgZmlsdGVyPSJ1cmwoI2EpIiBvcGFjaXR5PSIuMDUiLz48L3N2Zz4=')"
    />

    <!-- 径向渐变层（中心亮，四周暗） -->
    <div
      class="absolute inset-0"
      style="background: radial-gradient(ellipse at center, rgba(24, 24, 27, 0.6) 0%, rgba(9, 9, 11, 0.95) 70%, rgba(0, 0, 0, 1) 100%)"
    />

    <!-- 极光发光体 - 左上蓝色 -->
    <div
      class="absolute -top-40 -left-40 w-[600px] h-[600px] rounded-full opacity-20"
      style="background: radial-gradient(circle, #0EA5E9 0%, transparent 70%); filter: blur(200px);"
    />

    <!-- 极光发光体 - 右下金色 -->
    <div
      class="absolute -bottom-40 -right-40 w-[600px] h-[600px] rounded-full opacity-15"
      style="background: radial-gradient(circle, #FCD34D 0%, transparent 70%); filter: blur(200px);"
    />

    <!-- ==================== 火力网网格线层 ==================== -->
    <svg class="absolute inset-0 w-full h-full pointer-events-none opacity-20">
      <!-- 垂直网格线 -->
      <line
        v-for="(x, i) in gridVertical"
        :key="`v-${i}`"
        :x1="x"
        y1="0"
        :x2="x"
        y2="100%"
        stroke="#0EA5E9"
        stroke-width="0.5"
        :opacity="0.3 + Math.sin(animationTime * 0.001 + i * 0.5) * 0.2"
      />
      <!-- 水平网格线 -->
      <line
        v-for="(y, i) in gridHorizontal"
        :key="`h-${i}`"
        x1="0"
        :y1="y"
        x2="100%"
        :y2="y"
        stroke="#0EA5E9"
        stroke-width="0.5"
        :opacity="0.3 + Math.cos(animationTime * 0.001 + i * 0.5) * 0.2"
      />

      <!-- 中心十字高亮 -->
      <line x1="50%" y1="0" x2="50%" y2="100%" stroke="#FCD34D" stroke-width="1" opacity="0.4" />
      <line x1="0" y1="50%" x2="100%" y2="50%" stroke="#FCD34D" stroke-width="1" opacity="0.4" />
    </svg>

    <!-- ==================== 雷达扫描层 ==================== -->
    <svg class="absolute inset-0 w-full h-full pointer-events-none">
      <defs>
        <!-- 雷达扫描渐变 -->
        <radialGradient id="radarGradient">
          <stop offset="0%" stop-color="#0EA5E9" stop-opacity="0.3" />
          <stop offset="50%" stop-color="#0EA5E9" stop-opacity="0.1" />
          <stop offset="100%" stop-color="#0EA5E9" stop-opacity="0" />
        </radialGradient>
      </defs>

      <!-- 雷达扫描扇形（三个不同转速） -->
      <g :transform="`translate(${screenWidth / 2}, ${screenHeight / 2})`">
        <!-- 雷达1：慢速 -->
        <path
          :d="radarPath1"
          fill="url(#radarGradient)"
          opacity="0.15"
        />
        <!-- 雷达2：中速 -->
        <path
          :d="radarPath2"
          fill="url(#radarGradient)"
          opacity="0.15"
        />
        <!-- 雷达3：快速 -->
        <path
          :d="radarPath3"
          fill="url(#radarGradient)"
          opacity="0.15"
        />
      </g>
    </svg>

    <!-- ==================== 引力波层 ==================== -->
    <svg class="absolute inset-0 w-full h-full pointer-events-none">
      <circle
        v-for="wave in gravityWaves"
        :key="wave.id"
        :cx="wave.x"
        :cy="wave.y"
        :r="wave.radius"
        fill="none"
        stroke="rgba(14, 165, 233, 0.25)"
        :stroke-width="1.5"
        :opacity="wave.opacity"
        stroke-linecap="round"
      />
    </svg>

    <!-- ==================== Canvas 粒子系统层 ==================== -->
    <canvas
      ref="particleCanvas"
      class="absolute inset-0 pointer-events-none"
      :width="screenWidth"
      :height="screenHeight"
    />

    <!-- ==================== 弹幕轨迹层 ==================== -->
    <svg class="absolute inset-0 w-full h-full pointer-events-none">
      <line
        v-for="trail in barrageTrails"
        :key="trail.id"
        :x1="trail.x1"
        :y1="trail.y1"
        :x2="trail.x2"
        :y2="trail.y2"
        :stroke="trail.color"
        stroke-width="2"
        :opacity="trail.opacity"
        stroke-linecap="round"
      />
    </svg>

    <!-- ==================== 鼠标跟随光晕层 ==================== -->
    <div
      v-for="(trail, index) in mouseTrails"
      :key="trail.id"
      class="absolute pointer-events-none rounded-full"
      :style="{
        left: `${trail.x}px`,
        top: `${trail.y}px`,
        width: '40px',
        height: '40px',
        background: 'radial-gradient(circle, rgba(14, 165, 233, 0.4) 0%, transparent 70%)',
        filter: 'blur(10px)',
        opacity: trail.opacity,
        transform: 'translate(-50%, -50%)',
        transition: index === 0 ? 'all 0.1s cubic-bezier(0.34, 1.56, 0.64, 1)' : 'none'
      }"
    />

    <!-- ==================== 主内容层 ==================== -->
    <div class="relative z-10 flex flex-col items-center justify-center w-full h-full">

      <!-- BARRAGE 标题 -->
      <div class="absolute top-[20%] left-1/2 -translate-x-1/2">
        <div class="relative flex gap-3">
          <!-- 每个字母 -->
          <div
            v-for="(letter, index) in barrageLetters"
            :key="index"
            class="relative cursor-pointer select-none"
            @click.stop="handleLetterClick(index)"
          >
            <!-- 字母容器 -->
            <div
              class="relative transition-transform duration-300 ease-out"
              :style="{
                transform: `scale(${letter.pressed ? 0.9 : 1})`
              }"
            >
              <!-- 艺术字 -->
              <div class="relative">
                <span
                  class="block text-9xl font-black tracking-wider"
                  :style="{
                    color: '#0EA5E9',
                    fontFamily: 'Arial Black, Helvetica, Impact, sans-serif',
                    fontWeight: 900,
                    textTransform: 'uppercase',
                    letterSpacing: '0.15em',
                    WebkitTextStroke: '5px rgba(255, 255, 255, 0.98)',
                    paintOrder: 'stroke fill',
                    filter: 'drop-shadow(0 2px 8px rgba(14, 165, 233, 0.3))',
                    position: 'relative'
                  }"
                >
                  {{ letter.char }}
                </span>
              </div>

            </div>
          </div>
        </div>

        <!-- 底部装饰线 -->
        <div class="absolute left-0 right-0 h-1 mt-8" :style="{
          background: 'linear-gradient(90deg, transparent 0%, rgba(14, 165, 233, 0.6) 20%, rgba(255, 255, 255, 0.8) 50%, rgba(252, 211, 77, 0.6) 80%, transparent 100%)',
          boxShadow: '0 0 15px rgba(14, 165, 233, 0.6), 0 0 30px rgba(255, 255, 255, 0.3)'
        }" />
      </div>

      <!-- 文档按钮 - 右上角 -->
      <div class="absolute top-6 right-6"
           :class="buttonVisible ? 'opacity-100' : 'opacity-0'"
           :style="{ transition: 'opacity 0.8s ease-out 3.5s' }">
        <button
          @click="handleDocClick"
          class="group relative px-6 py-3 rounded-xl bg-white/5 backdrop-blur-md border border-white/10
                 hover:bg-white/10 hover:border-barrage-blue/50 transition-all duration-300
                 flex items-center gap-2 text-white/80 hover:text-white"
        >
          <svg
            class="w-5 h-5 transition-transform group-hover:scale-110"
            fill="none"
            stroke="currentColor"
            viewBox="0 0 24 24"
          >
            <path
              stroke-linecap="round"
              stroke-linejoin="round"
              stroke-width="2"
              d="M9 12h6m-6 4h6m2 5H7a2 2 0 01-2-2V5a2 2 0 012-2h5.586a1 1 0 01.707.293l5.414 5.414a1 1 0 01.293.707V19a2 2 0 01-2 2z"
            />
          </svg>
          <span class="text-sm font-medium">项目文档</span>
        </button>
      </div>

      <!-- 开始按钮 - 屏幕中心 -->
      <div
        v-show="buttonVisible"
        ref="startButtonRef"
        class="relative group cursor-pointer"
        :style="buttonStyle"
        @mouseenter="handleButtonHover"
        @mouseleave="handleButtonLeave"
        @click="handleButtonClick"
      >
        <!-- 呼吸光晕层 -->
        <div
          class="absolute inset-0 rounded-xl opacity-60 animate-pulse-slow"
          style="background: linear-gradient(90deg, #0EA5E9 0%, #3B82F6 50%, #0EA5E9 100%);
                 filter: blur(30px);
                 transform: scale(1.2);"
        />

        <!-- 边框光晕扩散层（Hover 时激活） -->
        <div
          :class="['absolute inset-0 rounded-xl transition-all duration-500',
                   isButtonHovered ? 'opacity-100 scale-110' : 'opacity-0 scale-100']"
          style="background: linear-gradient(90deg, #0EA5E9 0%, #FCD34D 100%);
                 filter: blur(40px);"
        />

        <!-- 按钮主体 -->
        <button
          :class="[
            'relative w-[200px] h-[60px] rounded-xl font-semibold text-lg',
            'bg-gradient-to-r from-barrage-blue to-blue-600',
            'text-white shadow-2xl',
            'border border-white/20',
            'transition-shadow duration-300',
            'hover:shadow-[0_0_50px_rgba(14,165,233,0.6)]',
            isButtonPressed ? 'scale-95 transition-transform duration-150' : ''
          ]"
        >
          <span class="relative z-10">启动</span>

          <!-- 涟漪波纹效果 -->
          <span
            v-for="ripple in ripples"
            :key="ripple.id"
            class="absolute inset-0 rounded-xl pointer-events-none"
            :style="{
              background: 'radial-gradient(circle, rgba(255,255,255,0.4) 0%, transparent 70%)',
              transform: `scale(${ripple.scale})`,
              opacity: ripple.opacity
            }"
          />
        </button>

        <!-- 粒子散发效果（Hover 时激活） -->
        <div
          v-for="particle in hoverParticles"
          :key="particle.id"
          class="absolute w-2 h-2 rounded-full pointer-events-none"
          :style="{
            left: '50%',
            top: '50%',
            background: particle.color,
            filter: 'blur(1px)',
            transform: `translate(${particle.x}px, ${particle.y}px) scale(${particle.scale})`,
            opacity: particle.opacity,
            transition: 'all 0.05s linear'
          }"
        />

        <!-- 气泡特效（入水时激活） -->
        <div
          v-for="bubble in bubbles"
          :key="bubble.id"
          class="absolute rounded-full pointer-events-none"
          :style="{
            left: '50%',
            top: '50%',
            width: '12px',
            height: '12px',
            background: 'radial-gradient(circle at 30% 30%, rgba(14, 165, 233, 0.8), rgba(14, 165, 233, 0.3))',
            border: '1px solid rgba(14, 165, 233, 0.4)',
            filter: 'blur(0.5px)',
            transform: `translate(${bubble.x}px, ${bubble.y}px) scale(${bubble.scale})`,
            opacity: bubble.opacity,
            boxShadow: '0 0 8px rgba(14, 165, 233, 0.3)'
          }"
        />
      </div>

    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted, onUnmounted, computed } from 'vue'
import { useRouter } from 'vue-router'

// ==================== 路由 ====================

const router = useRouter()

// ==================== 类型定义 ====================

interface MouseTrail {
  id: number
  x: number
  y: number
  opacity: number
}

interface Ripple {
  id: number
  scale: number
  opacity: number
}

interface HoverParticle {
  id: number
  x: number
  y: number
  scale: number
  opacity: number
  color: string
  vx: number
  vy: number
}

interface Bubble {
  id: number
  x: number
  y: number
  scale: number
  opacity: number
  vx: number
  vy: number
}

interface Particle {
  x: number
  y: number
  vx: number
  vy: number
  opacity: number
  baseOpacity: number
  size: number
  color: string
  originalX: number  // 原始位置X
  originalY: number  // 原始位置Y
}

interface BarrageTrail {
  id: number
  x1: number
  y1: number
  x2: number
  y2: number
  color: string
  opacity: number
  speed: number
  angle: number
}

interface GravityWave {
  id: number
  x: number
  y: number
  radius: number
  maxRadius: number
  opacity: number
  speed: number
}

interface BarrageLetter {
  char: string
  pressed: boolean
  isAnimating: boolean  // 防抖标志
}

// ==================== 状态管理 ====================

// 屏幕尺寸
const screenWidth = ref(window.innerWidth)
const screenHeight = ref(window.innerHeight)

// Canvas 粒子系统
const particleCanvas = ref<HTMLCanvasElement | null>(null)
const particles = ref<Particle[]>([])
const PARTICLE_COUNT = 350 // 大幅增加粒子数量以显示引力波效果

// 火力网网格线
const gridVertical = computed(() => {
  const spacing = screenWidth.value / 20
  return Array.from({ length: 20 }, (_, i) => i * spacing)
})
const gridHorizontal = computed(() => {
  const spacing = screenHeight.value / 12
  return Array.from({ length: 12 }, (_, i) => i * spacing)
})

// 雷达扫描（三个不同转速）
const radarAngle1 = ref(0)
const radarAngle2 = ref(Math.PI * 0.66)
const radarAngle3 = ref(Math.PI * 1.33)

const createRadarPath = (angle: number) => {
  const radius = Math.max(screenWidth.value, screenHeight.value) * 0.6
  const x1 = 0
  const y1 = 0
  const x2 = Math.cos(angle) * radius
  const y2 = Math.sin(angle) * radius
  const x3 = Math.cos(angle + Math.PI / 3) * radius // 60° 扇形
  const y3 = Math.sin(angle + Math.PI / 3) * radius

  return `M ${x1} ${y1} L ${x2} ${y2} A ${radius} ${radius} 0 0 1 ${x3} ${y3} Z`
}

const radarPath1 = computed(() => createRadarPath(radarAngle1.value))
const radarPath2 = computed(() => createRadarPath(radarAngle2.value))
const radarPath3 = computed(() => createRadarPath(radarAngle3.value))

// 弹幕轨迹
const barrageTrails = ref<BarrageTrail[]>([])
let barrageTrailIdCounter = 0

// 引力波系统
const gravityWaves = ref<GravityWave[]>([])
let gravityWaveIdCounter = 0
const MAX_GRAVITY_WAVES = 4  // 最大引力波数量
let lastWaveTime = 0  // 上次创建引力波的时间
const WAVE_COOLDOWN = 300  // 引力波冷却时间（毫秒）

// BARRAGE 标题系统
const barrageLetters = ref<BarrageLetter[]>([
  { char: 'B', pressed: false, isAnimating: false },
  { char: 'A', pressed: false, isAnimating: false },
  { char: 'R', pressed: false, isAnimating: false },
  { char: 'R', pressed: false, isAnimating: false },
  { char: 'A', pressed: false, isAnimating: false },
  { char: 'G', pressed: false, isAnimating: false },
  { char: 'E', pressed: false, isAnimating: false }
])

// 动画时间（用于网格线闪烁）
const animationTime = ref(0)

// 鼠标轨迹残影
const mouseTrails = ref<MouseTrail[]>([])
let trailIdCounter = 0
const MAX_TRAILS = 8
const mouseX = ref(0)
const mouseY = ref(0)
let lastTrailTime = 0 // 添加节流

// 按钮状态
const startButtonRef = ref<HTMLElement | null>(null)
const isButtonHovered = ref(false)
const isButtonPressed = ref(false)
const buttonVisible = ref(false)

// 物理动画参数
const buttonY = ref(-100)
const buttonX = ref(0)
const buttonRotate = ref(0)

const buttonStyle = computed(() => ({
  transform: `translateY(${buttonY.value}vh) translateX(${buttonX.value}px) rotate(${buttonRotate.value}deg)`
}))

// 涟漪波纹
const ripples = ref<Ripple[]>([])
let rippleIdCounter = 0

// Hover 粒子散发
const hoverParticles = ref<HoverParticle[]>([])
let hoverParticleIdCounter = 0
let particleAnimationFrame: number | null = null

// 气泡特效
const bubbles = ref<Bubble[]>([])
let bubbleIdCounter = 0

// 动画循环标识
let animationFrameId: number | null = null
let lastFrameTime = 0
const targetFPS = 60
const frameInterval = 1000 / targetFPS

// ==================== Canvas 粒子系统 ====================

/**
 * 初始化粒子系统
 */
const initParticles = () => {
  particles.value = []

  for (let i = 0; i < PARTICLE_COUNT; i++) {
    const x = Math.random() * screenWidth.value
    const y = Math.random() * screenHeight.value
    particles.value.push({
      x,
      y,
      vx: (Math.random() - 0.5) * 0.5,
      vy: (Math.random() - 0.5) * 0.5,
      opacity: 0.4,
      baseOpacity: 0.3 + Math.random() * 0.3,
      size: 1.5 + Math.random() * 1,
      color: Math.random() > 0.7 ? '#FCD34D' : '#0EA5E9',
      originalX: x,  // 记录原始位置
      originalY: y   // 记录原始位置
    })
  }
}

/**
 * 更新粒子位置（性能优化版）
 */
const updateParticles = () => {
  const mouseRepulseRadiusSq = 120 * 120 // 预计算平方值
  const activeWaves = gravityWaves.value // 缓存引用

  particles.value.forEach(p => {
    // 位置更新
    p.x += p.vx
    p.y += p.vy

    // 边界反弹
    if (p.x < 0 || p.x > screenWidth.value) p.vx *= -1
    if (p.y < 0 || p.y > screenHeight.value) p.vy *= -1

    // 鼠标排斥效果（Repulse）- 使用平方距离避免sqrt
    const dx = p.x - mouseX.value
    const dy = p.y - mouseY.value
    const distSq = dx * dx + dy * dy

    if (distSq < mouseRepulseRadiusSq && distSq > 0) {
      const distance = Math.sqrt(distSq)
      const force = (120 - distance) / 120
      p.x += (dx / distance) * force * 3
      p.y += (dy / distance) * force * 3
    }

    // 引力波排斥效果 - 只对存在的引力波计算
    if (activeWaves.length > 0) {
      for (let i = 0; i < activeWaves.length; i++) {
        const wave = activeWaves[i]
        const wdx = p.x - wave.x
        const wdy = p.y - wave.y
        const wdistSq = wdx * wdx + wdy * wdy
        const wdist = Math.sqrt(wdistSq)

        // 粒子在引力波环附近时被轻微排斥
        const waveDiff = Math.abs(wdist - wave.radius)
        if (waveDiff < 40 && wdist > 0) {
          // 力度随着距离波源的距离而衰减
          const distanceFalloff = Math.max(0, 1 - wave.radius / wave.maxRadius)
          const repelForce = (40 - waveDiff) / 40 * 4.5 * distanceFalloff
          p.x += (wdx / wdist) * repelForce
          p.y += (wdy / wdist) * repelForce
        }
      }
    }

    // 回弹力：粒子会缓慢回到原始位置
    const restoreDx = p.originalX - p.x
    const restoreDy = p.originalY - p.y
    const restoreDistSq = restoreDx * restoreDx + restoreDy * restoreDy

    if (restoreDistSq > 1) {
      const restoreDistance = Math.sqrt(restoreDistSq)
      // 恢复力：距离原位越远，恢复力越大
      const restoreForce = Math.min(restoreDistance * 0.015, 0.8)
      p.x += (restoreDx / restoreDistance) * restoreForce
      p.y += (restoreDy / restoreDistance) * restoreForce
    }

    // 保持在边界内
    p.x = Math.max(0, Math.min(screenWidth.value, p.x))
    p.y = Math.max(0, Math.min(screenHeight.value, p.y))
  })
}

/**
 * 渲染粒子和连线（性能优化版）
 */
const renderParticles = () => {
  const canvas = particleCanvas.value
  if (!canvas) return

  const ctx = canvas.getContext('2d', {
    alpha: true,
    desynchronized: true // 启用低延迟渲染
  })
  if (!ctx) return

  // 清空画布
  ctx.clearRect(0, 0, screenWidth.value, screenHeight.value)

  // 空间分区优化：将屏幕划分为网格
  const gridSize = 150 // 连线最大距离
  const cols = Math.ceil(screenWidth.value / gridSize)
  const rows = Math.ceil(screenHeight.value / gridSize)
  const grid: Particle[][] = Array.from({ length: cols * rows }, () => [])

  // 将粒子分配到网格
  particles.value.forEach(p => {
    const col = Math.floor(p.x / gridSize)
    const row = Math.floor(p.y / gridSize)
    const index = row * cols + col
    if (index >= 0 && index < grid.length) {
      grid[index].push(p)
    }
  })

  // 绘制粒子连线（只检查相邻网格）
  ctx.strokeStyle = '#0EA5E9'
  ctx.lineWidth = 0.5

  const checked = new Set<string>()

  for (let i = 0; i < particles.value.length; i++) {
    const p1 = particles.value[i]
    const col = Math.floor(p1.x / gridSize)
    const row = Math.floor(p1.y / gridSize)

    // 检查当前网格和相邻8个网格
    for (let dx = -1; dx <= 1; dx++) {
      for (let dy = -1; dy <= 1; dy++) {
        const neighborCol = col + dx
        const neighborRow = row + dy
        const neighborIndex = neighborRow * cols + neighborCol

        if (neighborIndex >= 0 && neighborIndex < grid.length) {
          const neighbors = grid[neighborIndex]

          for (const p2 of neighbors) {
            // 避免重复检查
            const pairKey = `${Math.min(i, particles.value.indexOf(p2))}-${Math.max(i, particles.value.indexOf(p2))}`
            if (checked.has(pairKey) || p1 === p2) continue
            checked.add(pairKey)

            const dx = p1.x - p2.x
            const dy = p1.y - p2.y
            const distSq = dx * dx + dy * dy // 使用平方距离避免sqrt

            if (distSq < 150 * 150) {
              const distance = Math.sqrt(distSq)
              ctx.globalAlpha = (1 - distance / 150) * 0.3
              ctx.beginPath()
              ctx.moveTo(p1.x, p1.y)
              ctx.lineTo(p2.x, p2.y)
              ctx.stroke()
            }
          }
        }
      }
    }
  }

  // 绘制粒子（批量渲染同颜色）
  const blueParticles = particles.value.filter(p => p.color === '#0EA5E9')
  const goldParticles = particles.value.filter(p => p.color === '#FCD34D')

  // 绘制蓝色粒子
  ctx.fillStyle = '#0EA5E9'
  blueParticles.forEach(p => {
    ctx.globalAlpha = p.opacity
    ctx.beginPath()
    ctx.arc(p.x, p.y, p.size, 0, Math.PI * 2)
    ctx.fill()
  })

  // 绘制金色粒子
  ctx.fillStyle = '#FCD34D'
  goldParticles.forEach(p => {
    ctx.globalAlpha = p.opacity
    ctx.beginPath()
    ctx.arc(p.x, p.y, p.size, 0, Math.PI * 2)
    ctx.fill()
  })
}

/**
 * 点击效果：创建引力波（带防抖和数量限制）
 */
const handleCanvasClick = (event: MouseEvent) => {
  const now = performance.now()

  // 防抖：距离上次创建不足300ms，忽略
  if (now - lastWaveTime < WAVE_COOLDOWN) {
    return
  }

  // 数量限制：已有4个引力波，忽略
  if (gravityWaves.value.length >= MAX_GRAVITY_WAVES) {
    return
  }

  const clickX = event.clientX
  const clickY = event.clientY

  // 创建引力波
  createGravityWave(clickX, clickY)

  // 更新上次创建时间
  lastWaveTime = now
}

// ==================== 引力波系统 ====================

/**
 * 创建引力波
 */
const createGravityWave = (x: number, y: number) => {
  // 计算到屏幕最远角的距离，确保覆盖全屏
  const maxRadius = Math.sqrt(
    Math.max(x, screenWidth.value - x) ** 2 +
    Math.max(y, screenHeight.value - y) ** 2
  ) * 1.5

  const wave: GravityWave = {
    id: gravityWaveIdCounter++,
    x,
    y,
    radius: 0,
    maxRadius,  // 动态计算最大半径以覆盖全页面
    opacity: 0.5,  // 提高初始透明度使其更明显
    speed: 6  // 稍微加快扩散速度
  }

  gravityWaves.value.push(wave)
  animateGravityWave(wave)
}

/**
 * 引力波动画
 */
const animateGravityWave = (wave: GravityWave) => {
  const animate = () => {
    const w = gravityWaves.value.find(w => w.id === wave.id)
    if (!w) return

    // 扩散
    w.radius += w.speed

    // 淡出 - 使用平方衰减，让引力越来越小
    const progress = w.radius / w.maxRadius
    w.opacity = Math.max(0, 0.5 * Math.pow(1 - progress, 2))

    if (w.radius < w.maxRadius) {
      requestAnimationFrame(animate)
    } else {
      gravityWaves.value = gravityWaves.value.filter(gw => gw.id !== w.id)
    }
  }

  requestAnimationFrame(animate)
}

// ==================== 弹幕轨迹系统 ====================

/**
 * 触发弹幕轨迹效果
 */
const triggerBarrageTrails = (centerX: number, centerY: number) => {
  const trailCount = 8

  for (let i = 0; i < trailCount; i++) {
    const angle = (Math.PI * 2 / trailCount) * i + Math.random() * 0.3
    const speed = 3 + Math.random() * 2
    const length = 50 + Math.random() * 30

    const trail: BarrageTrail = {
      id: barrageTrailIdCounter++,
      x1: centerX,
      y1: centerY,
      x2: centerX,
      y2: centerY,
      color: Math.random() > 0.5 ? '#0EA5E9' : '#FCD34D',
      opacity: 0.8,
      speed,
      angle
    }

    barrageTrails.value.push(trail)
    animateBarrageTrail(trail, length)
  }
}

/**
 * 弹幕轨迹动画
 */
const animateBarrageTrail = (trail: BarrageTrail, maxLength: number) => {
  let currentLength = 0

  const animate = () => {
    const t = barrageTrails.value.find(t => t.id === trail.id)
    if (!t) return

    // 扩展轨迹
    currentLength += t.speed
    t.x2 = t.x1 + Math.cos(t.angle) * currentLength
    t.y2 = t.y1 + Math.sin(t.angle) * currentLength

    // 淡出
    t.opacity -= 0.02

    if (currentLength < maxLength && t.opacity > 0) {
      requestAnimationFrame(animate)
    } else {
      barrageTrails.value = barrageTrails.value.filter(tr => tr.id !== t.id)
    }
  }

  requestAnimationFrame(animate)
}

// ==================== BARRAGE 标题交互系统 ====================

/**
 * 字母点击处理 - 带防抖
 */
const handleLetterClick = (index: number) => {
  const letter = barrageLetters.value[index]

  // 防抖：如果正在动画中，忽略点击
  if (letter.isAnimating) return

  // 设置动画标志
  letter.isAnimating = true

  // 压缩效果
  letter.pressed = true

  // 短暂压缩后弹回
  setTimeout(() => {
    letter.pressed = false
  }, 150)

  // 动画结束后解除锁定
  setTimeout(() => {
    letter.isAnimating = false
  }, 300)
}

/**
 * 随机生成弹幕轨迹（模拟持续的弹幕效果）- 性能优化版
 */
const spawnRandomBarrageTrail = () => {
  // 降低生成频率从3%到1.5%
  if (Math.random() > 0.985) {
    const edge = Math.floor(Math.random() * 4) // 0:上, 1:右, 2:下, 3:左
    let x1, y1, angle

    switch (edge) {
      case 0: // 从上边缘
        x1 = Math.random() * screenWidth.value
        y1 = 0
        angle = Math.PI / 2 + (Math.random() - 0.5) * Math.PI / 3
        break
      case 1: // 从右边缘
        x1 = screenWidth.value
        y1 = Math.random() * screenHeight.value
        angle = Math.PI + (Math.random() - 0.5) * Math.PI / 3
        break
      case 2: // 从下边缘
        x1 = Math.random() * screenWidth.value
        y1 = screenHeight.value
        angle = -Math.PI / 2 + (Math.random() - 0.5) * Math.PI / 3
        break
      default: // 从左边缘
        x1 = 0
        y1 = Math.random() * screenHeight.value
        angle = (Math.random() - 0.5) * Math.PI / 3
    }

    const trail: BarrageTrail = {
      id: barrageTrailIdCounter++,
      x1,
      y1,
      x2: x1,
      y2: y1,
      color: Math.random() > 0.7 ? '#FCD34D' : '#0EA5E9',
      opacity: 0.4,
      speed: 2 + Math.random() * 3,
      angle
    }

    barrageTrails.value.push(trail)
    animateBarrageTrail(trail, 100 + Math.random() * 100)
  }
}

// ==================== 雷达扫描动画 ====================

const updateRadar = () => {
  // 三个不同转速的雷达
  radarAngle1.value += 0.008  // 慢速
  radarAngle2.value += 0.012  // 中速
  radarAngle3.value += 0.016  // 快速

  if (radarAngle1.value > Math.PI * 2) radarAngle1.value -= Math.PI * 2
  if (radarAngle2.value > Math.PI * 2) radarAngle2.value -= Math.PI * 2
  if (radarAngle3.value > Math.PI * 2) radarAngle3.value -= Math.PI * 2
}

// ==================== 主动画循环（性能优化版） ====================

const mainAnimationLoop = (currentTime: number = 0) => {
  // 帧率控制
  const elapsed = currentTime - lastFrameTime

  if (elapsed >= frameInterval) {
    lastFrameTime = currentTime - (elapsed % frameInterval)

    animationTime.value += elapsed

    updateParticles()
    renderParticles()
    updateRadar()
    spawnRandomBarrageTrail()
    // 引力波在各自的 requestAnimationFrame 中更新
  }

  animationFrameId = requestAnimationFrame(mainAnimationLoop)
}

// ==================== 鼠标跟随光晕（性能优化版） ====================

const handleMouseMove = (event: MouseEvent) => {
  mouseX.value = event.clientX
  mouseY.value = event.clientY

  // 节流：每30ms更新一次轨迹
  const now = performance.now()
  if (now - lastTrailTime < 30) return
  lastTrailTime = now

  const newTrail: MouseTrail = {
    id: trailIdCounter++,
    x: event.clientX,
    y: event.clientY,
    opacity: 1
  }

  mouseTrails.value.unshift(newTrail)

  if (mouseTrails.value.length > MAX_TRAILS) {
    mouseTrails.value.pop()
  }

  // 批量更新透明度
  const len = mouseTrails.value.length
  for (let i = 1; i < len; i++) {
    mouseTrails.value[i].opacity = Math.max(0, 1 - i * 0.15)
  }
}

// ==================== 开始按钮物理动画 ====================

const triggerPhysicsAnimation = () => {
  const startTime = performance.now()
  const fallDuration = 900
  const bounceStartTime = fallDuration
  const bounceDuration = 2500
  const bounceCount = 3
  const initialBounceHeight = 15
  const yDamping = 0.4
  const initialSwingAmplitude = 40
  const initialRotateAmplitude = 8
  const swingFrequency = 3
  const rotateFrequency = 4
  let hasTriggeredBubbles = false

  const animate = (currentTime: number) => {
    const elapsed = currentTime - startTime

    if (elapsed < fallDuration) {
      const progress = elapsed / fallDuration
      const easeInCubic = (t: number) => t * t * t
      const easedProgress = easeInCubic(progress)
      buttonY.value = -100 + (100 * easedProgress)
      buttonRotate.value = easedProgress * 5
      requestAnimationFrame(animate)
    }
    else if (elapsed < bounceStartTime + bounceDuration) {
      const bounceElapsed = elapsed - bounceStartTime
      const bounceProgress = bounceElapsed / bounceDuration

      if (!hasTriggeredBubbles) {
        triggerBubbles()
        hasTriggeredBubbles = true
      }

      const yCycle = Math.floor(bounceProgress * bounceCount)
      const yAmplitude = initialBounceHeight * Math.pow(yDamping, yCycle)
      const yOscillation = Math.sin(bounceProgress * bounceCount * Math.PI * 2)
      buttonY.value = yAmplitude * yOscillation

      const xEnvelope = Math.exp(-bounceProgress * 2.5)
      const xOscillation = Math.sin(bounceProgress * swingFrequency * Math.PI * 2)
      buttonX.value = initialSwingAmplitude * xEnvelope * xOscillation

      const rotateEnvelope = Math.exp(-bounceProgress * 2.5)
      const rotateOscillation = Math.cos(bounceProgress * rotateFrequency * Math.PI * 2)
      buttonRotate.value = initialRotateAmplitude * rotateEnvelope * rotateOscillation

      requestAnimationFrame(animate)
    }
    else {
      buttonY.value = 0
      buttonX.value = 0
      buttonRotate.value = 0
    }
  }

  requestAnimationFrame(animate)
}

const triggerBubbles = () => {
  const bubbleCount = 12

  for (let i = 0; i < bubbleCount; i++) {
    setTimeout(() => {
      const bubble: Bubble = {
        id: bubbleIdCounter++,
        x: -50 + Math.random() * 100,
        y: 0,
        scale: 0.5 + Math.random() * 0.8,
        opacity: 0.6 + Math.random() * 0.4,
        vx: (Math.random() - 0.5) * 1,
        vy: -1.5 - Math.random() * 1.5
      }

      bubbles.value.push(bubble)
      animateBubble(bubble)
    }, i * 60)
  }
}

const animateBubble = (bubble: Bubble) => {
  const animate = () => {
    const b = bubbles.value.find(b => b.id === bubble.id)
    if (!b) return

    b.y += b.vy
    b.x += b.vx
    b.vy -= 0.02
    b.vx += (Math.random() - 0.5) * 0.1
    b.vx *= 0.98
    b.opacity -= 0.008
    b.scale -= 0.003

    if (b.opacity > 0 && b.y > -200) {
      requestAnimationFrame(animate)
    } else {
      bubbles.value = bubbles.value.filter(bubble => bubble.id !== b.id)
    }
  }

  requestAnimationFrame(animate)
}

const handleButtonHover = () => {
  isButtonHovered.value = true
  startHoverParticles()
}

const handleButtonLeave = () => {
  isButtonHovered.value = false
  stopHoverParticles()
}

const startHoverParticles = () => {
  const generateParticle = () => {
    if (!isButtonHovered.value) return

    const angle = Math.random() * Math.PI * 2
    const radius = 100
    const speed = 1 + Math.random() * 2

    const particle: HoverParticle = {
      id: hoverParticleIdCounter++,
      x: Math.cos(angle) * radius,
      y: Math.sin(angle) * radius,
      vx: Math.cos(angle) * speed,
      vy: Math.sin(angle) * speed,
      scale: 0.8 + Math.random() * 0.4,
      opacity: 1,
      color: Math.random() > 0.5 ? '#0EA5E9' : '#FCD34D'
    }

    hoverParticles.value.push(particle)

    const animateParticle = () => {
      const p = hoverParticles.value.find(p => p.id === particle.id)
      if (!p) return

      p.x += p.vx
      p.y += p.vy
      p.opacity -= 0.02
      p.scale -= 0.01

      if (p.opacity > 0) {
        requestAnimationFrame(animateParticle)
      } else {
        hoverParticles.value = hoverParticles.value.filter(hp => hp.id !== p.id)
      }
    }

    animateParticle()
  }

  particleAnimationFrame = window.setInterval(generateParticle, 80) as any
}

const stopHoverParticles = () => {
  if (particleAnimationFrame) {
    clearInterval(particleAnimationFrame)
    particleAnimationFrame = null
  }
  hoverParticles.value = []
}

const handleButtonClick = () => {
  isButtonPressed.value = true
  setTimeout(() => {
    isButtonPressed.value = false
  }, 150)

  const ripple: Ripple = {
    id: rippleIdCounter++,
    scale: 0,
    opacity: 0.6
  }

  ripples.value.push(ripple)

  const animateRipple = () => {
    const r = ripples.value.find(r => r.id === ripple.id)
    if (!r) return

    r.scale += 0.1
    r.opacity -= 0.02

    if (r.opacity > 0) {
      requestAnimationFrame(animateRipple)
    } else {
      ripples.value = ripples.value.filter(rp => rp.id !== r.id)
    }
  }

  animateRipple()

  console.log('🚀 启动压测引擎...')

  // 延迟跳转以展示动画效果
  setTimeout(() => {
    router.push('/main')
  }, 600)
}

const handleDocClick = () => {
  console.log('📖 打开项目文档...')
}

// ==================== 窗口大小调整 ====================

const handleResize = () => {
  screenWidth.value = window.innerWidth
  screenHeight.value = window.innerHeight

  if (particleCanvas.value) {
    particleCanvas.value.width = screenWidth.value
    particleCanvas.value.height = screenHeight.value
  }
}

// ==================== 生命周期 ====================

onMounted(() => {
  window.addEventListener('mousemove', handleMouseMove)
  window.addEventListener('resize', handleResize)
  window.addEventListener('click', handleCanvasClick)

  // 初始化粒子系统
  initParticles()

  // 启动主动画循环
  mainAnimationLoop()

  // 开场动画序列
  setTimeout(() => {
    buttonVisible.value = true

    setTimeout(() => {
      triggerPhysicsAnimation()
    }, 300)
  }, 100)

  console.log('✅ Barrage 首页已加载（完整版 v4 - 性能优化）')
  console.log('📊 粒子数量: 350 | 雷达数量: 3 | 引力波系统: 已激活（可见排斥+回弹）')
  console.log('⚡ 性能优化: 空间分区算法 | 平方距离计算 | 批量渲染 | 帧率控制 | 鼠标节流')
})

onUnmounted(() => {
  window.removeEventListener('mousemove', handleMouseMove)
  window.removeEventListener('resize', handleResize)
  window.removeEventListener('click', handleCanvasClick)
  stopHoverParticles()

  if (animationFrameId) {
    cancelAnimationFrame(animationFrameId)
  }
})
</script>

<style scoped>
.relative,
.absolute {
  will-change: transform;
}

button {
  will-change: transform, box-shadow;
}
</style>
