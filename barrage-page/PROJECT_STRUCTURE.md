# Barrage 火力网压测引擎 - 前端项目结构

## 📁 目录结构

```
src/
├── assets/                 # 静态资源（图片、字体等）
├── components/             # 可复用组件
│   ├── common/            # 通用组件
│   │   └── SaveRequestModal.vue    # 保存请求模态框
│   └── tree/              # 树形组件
│       ├── FolderTreeNode.vue      # 文件夹树节点（递归）
│       └── FullTreeNode.vue        # 完整树节点（文件夹+请求，递归）
├── views/                  # 页面组件
│   ├── home/              # 首页
│   │   └── HomePage.vue            # 首页（启动页面）
│   ├── main/              # 主界面
│   │   ├── MainLayout.vue          # 主布局（包含导航栏）
│   │   └── RequestsPage.vue        # 请求管理页面
│   └── test/              # 测试相关页面
│       └── ApiTest.vue             # 接口测试页面
├── router/                 # 路由配置
│   └── index.ts                    # 路由定义
├── stores/                 # 状态管理
│   └── requestStore.ts             # 请求管理全局状态（单例）
├── types/                  # TypeScript 类型定义
│   └── index.ts                    # 全局类型定义
├── utils/                  # 工具函数（待添加）
├── App.vue                 # 根组件
├── main.ts                 # 入口文件
└── style.css               # 全局样式
```

## 📄 文件说明

### 核心文件

| 文件 | 说明 |
|------|------|
| `App.vue` | 根组件，包含 `<RouterView>` |
| `main.ts` | 应用入口，初始化 Vue、Router、Motion |
| `style.css` | 全局 Tailwind CSS 样式 |

### 路由 (`router/`)

| 文件 | 说明 |
|------|------|
| `index.ts` | 路由配置：`/` → HomePage, `/main` → MainLayout |

### 状态管理 (`stores/`)

| 文件 | 说明 |
|------|------|
| `requestStore.ts` | 请求树全局状态（reactive 单例），localStorage 持久化 |
| `simulateStore.ts` | 模拟测试工作流图全局状态，支持节点和边管理 |

### 类型定义 (`types/`)

| 文件 | 说明 |
|------|------|
| `index.ts` | 全局类型：`HttpMethod`, `TestConfig`, `TreeNode` 等 |
| `simulate.ts` | 模拟测试类型：`GraphNode`, `Edge`, `ExecutionGraph` 等 |

### 页面组件 (`views/`)

#### `views/home/`
- `HomePage.vue` - 首页启动页面，包含粒子系统、雷达扫描等特效

#### `views/main/`
- `MainLayout.vue` - 主界面布局，包含左侧导航栏
- `RequestsPage.vue` - 请求管理页面，文件树展示和 CRUD 操作

#### `views/test/`
- `ApiTest.vue` - 接口测试页面，配置和执行 HTTP 请求
- `SimulateTestPage.vue` - 模拟测试页面，图形化工作流配置

### 可复用组件 (`components/`)

#### `components/common/`
- `SaveRequestModal.vue` - 保存请求模态框，文件夹选择和新建

#### `components/tree/`
- `FolderTreeNode.vue` - 文件夹树节点（递归组件，仅显示文件夹）
- `FullTreeNode.vue` - 完整树节点（递归组件，显示文件夹和请求）

## 🔄 导入路径规范

### 从页面组件导入

```typescript
// ✅ 正确示例
import type { TreeNode } from '../../types'
import { getRootNode } from '../../stores/requestStore'
import SaveRequestModal from '../../components/common/SaveRequestModal.vue'
```

### 从可复用组件导入

```typescript
// ✅ 正确示例
import type { TreeNode } from '../../types'
import FolderTreeNode from '../tree/FolderTreeNode.vue'
```

## 🎯 设计原则

### 1. **关注点分离**
- **views/** - 页面级组件，包含业务逻辑
- **components/** - 纯 UI 组件，可复用

### 2. **单一职责**
- 每个组件专注一个功能
- 树节点组件独立，可递归复用

### 3. **类型安全**
- 所有类型定义集中在 `types/`
- 使用 TypeScript 确保类型安全

### 4. **状态管理**
- 使用 reactive 创建全局单例
- localStorage 自动持久化
- 页面切换不丢失数据

## 📊 数据流

```
用户操作
    ↓
页面组件 (views/)
    ↓
调用 Store API (stores/requestStore.ts)
    ↓
更新全局状态 (reactive rootNode)
    ↓
自动保存 localStorage
    ↓
响应式更新所有组件
```

## 🔧 技术栈

- **Vue 3** - Composition API + `<script setup>`
- **TypeScript** - 类型安全
- **Vue Router 4** - 路由管理
- **Tailwind CSS** - 样式框架
- **VueUse Motion** - 动画库

## 📝 命名规范

### 组件命名
- PascalCase: `SaveRequestModal.vue`
- 描述性名称: `FolderTreeNode` 而非 `TreeNode1`

### 文件夹命名
- kebab-case: `common/`, `tree/`
- 复数形式: `components/`, `views/`, `types/`

### 类型命名
- PascalCase: `TreeNode`, `TestConfig`
- 枚举类型: `HttpMethod`, `BodyType`

## 🚀 下一步优化

1. [ ] 添加工具函数到 `utils/`
2. [ ] 添加单元测试
3. [ ] 添加 Storybook 文档
4. [ ] 性能优化（虚拟滚动、懒加载）
5. [ ] 添加 i18n 国际化支持
