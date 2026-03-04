# Barrage 火力网压测引擎 - 前端首页

基于 **Vue 3 + TypeScript + Tailwind CSS** 构建的科技感十足的高并发压测工具大屏首页。

## 🎨 设计特性

### 美术风格
- **未来主义**：柔和渐变、极简几何、玻璃态
- **配色方案**：
  - 主蓝色：`#0EA5E9` (Tailwind sky-500)
  - 冷金属金：`#FCD34D` (Tailwind yellow-300)
  - 深空背景：`bg-zinc-950`

### 视觉元素
1. **深空环境光**
   - 噪点纹理叠加
   - 径向渐变（中心亮，四周暗）
   - 左上蓝色 + 右下金色极光光晕（blur-200px）

2. **交互式粒子背景** (tsparticles)
   - 60 个粒子网格连线
   - 鼠标 Repulse 排斥效果（120px）
   - 点击火力网效果：80% 概率粒子消失后恢复

3. **开始按钮动效**
   - 尺寸：200x60px 圆角矩形
   - 静态：缓慢呼吸光晕（水平渐变）
   - Hover：
     - 浮力效果（模拟掉入水面的阻尼振荡）
     - 物理弹簧缩放（1.0 → 1.05）
     - 边框光晕扩散
     - 粒子从边缘散发
   - 点击：涟漪波纹 + 压缩回弹

4. **文档按钮**
   - 位置：右上角（top-6 right-6）
   - 样式：玻璃态 + 图标 + 文字
   - 预留点击扩展能力

5. **鼠标跟随光晕**
   - 40x40px 蓝色小光点
   - 100ms 延迟缓动
   - 轨迹渐隐残影（激光笔效果，最多 8 个残影）

## 🚀 快速开始

### 1. 安装依赖

```bash
cd barrage-page
npm install
```

### 2. 启动开发服务器

```bash
npm run dev
```

访问：`http://localhost:5173`

### 3. 构建生产版本

```bash
npm run build
```

## 📦 技术栈

### 核心框架
- **Vue 3.4+** - Composition API (<script setup>)
- **TypeScript 5.3+** - 类型安全
- **Vite 5.0+** - 极速构建工具

### 样式系统
- **Tailwind CSS 3.4+** - 原子化 CSS

### 动效库
- **@vueuse/motion** - 物理弹簧动效
- **@vueuse/core** - Vue 组合式工具集
- **@tsparticles/vue3** + **@tsparticles/slim** - 粒子系统

## 📂 项目结构

```
barrage-page/
├── index.html              # 入口 HTML
├── package.json            # 依赖配置
├── vite.config.ts          # Vite 构建配置
├── tsconfig.json           # TypeScript 配置
├── tailwind.config.js      # Tailwind 配置
├── postcss.config.js       # PostCSS 配置
└── src/
    ├── main.ts             # Vue 入口
    ├── App.vue             # 根组件
    ├── style.css           # 全局样式（Tailwind 导入）
    └── components/
        └── HomePage.vue    # 首页核心组件（单文件）
```

## 🎯 核心代码说明

### HomePage.vue 结构

```vue
<!-- 1. 背景层 -->
噪点纹理 → 径向渐变 → 极光光晕（左上蓝 + 右下金）

<!-- 2. 粒子层 -->
tsparticles 网格连线 + 鼠标交互 + 火力网点击效果

<!-- 3. 鼠标光晕层 -->
实时光晕 + 8 个轨迹残影（渐隐）

<!-- 4. 主内容层 -->
文档按钮（右上） + 开始按钮（中心）
```

### 关键动效实现

#### 1. 浮力效果（阻尼振荡）
```typescript
// 公式：y = A * e^(-damping * t) * cos(frequency * t)
const offset = initialDrop * Math.exp(-0.6 * time) * Math.cos(15 * time)
```

#### 2. 火力网点击效果
```typescript
// 点击后 80% 概率粒子消失 → 延迟 500ms → 恢复
if (distance < 200 && Math.random() < 0.8) {
  fadeOut() → wait(500ms) → fadeIn()
}
```

#### 3. 鼠标轨迹残影
```typescript
// 每次鼠标移动添加新轨迹点，保留最多 8 个
// 越老的轨迹透明度越低（opacity = 1 - index * 0.15）
mouseTrails.unshift(newTrail)
if (mouseTrails.length > 8) mouseTrails.pop()
```

## ⚙️ 性能优化

### 平衡性能模式
- tsparticles FPS 限制：30fps
- 中等粒子密度：60 个
- GPU 加速：will-change 属性
- 动画节流：requestAnimationFrame

### 响应式
- 仅适配桌面端（1920x1080 基准）
- 粒子数量根据屏幕密度自适应

## 🔧 扩展开发

### 添加新路由
当前为单页面，未来可集成 Vue Router：
```bash
npm install vue-router@4
```

### 连接后端 API
在 `handleButtonClick()` 中添加启动逻辑：
```typescript
const handleButtonClick = async () => {
  const response = await fetch('/api/start-test', {
    method: 'POST',
    body: JSON.stringify({ config: {...} })
  })
  // 跳转到实时监控页面
}
```

### 文档按钮跳转
在 `handleDocClick()` 中实现：
```typescript
const handleDocClick = () => {
  // 方案 1：打开侧边栏
  showDocSidebar.value = true

  // 方案 2：跳转外部链接
  window.open('https://github.com/LettuceLeaves/barrage', '_blank')

  // 方案 3：路由跳转
  router.push('/docs')
}
```

## 📝 开发注意事项

1. **TypeScript 严格模式已启用**，需要明确类型声明
2. **Tailwind 自定义类**：`bg-barrage-blue`、`bg-barrage-gold` 已在 config 中定义
3. **粒子自定义事件**：火力网效果通过注册 `externalCustomBarrage` 交互器实现
4. **浏览器兼容性**：需支持 ES2020+ 和 CSS Backdrop Filter

## 🐛 常见问题

### Q: 粒子不显示？
A: 确保 tsparticles 依赖正确安装：
```bash
npm install @tsparticles/vue3 @tsparticles/slim
```

### Q: 动效卡顿？
A: 检查浏览器是否启用硬件加速，或降低粒子数量（修改 `particlesOptions.particles.number.value`）

### Q: Tailwind 类不生效？
A: 确认 `postcss.config.js` 和 `style.css` 正确配置

## 📄 许可证

MIT License - 与 Barrage 主项目保持一致

## 🤝 贡献

欢迎提交 Issue 和 Pull Request！

---

**Powered by Vue 3 + Tailwind CSS + tsparticles**
*为 Barrage 火力网压测引擎量身打造的科技感首页* 🚀
