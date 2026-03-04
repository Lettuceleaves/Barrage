/**
 * Barrage 火力网压测引擎 - 路由配置
 */

import { createRouter, createWebHistory } from 'vue-router'
import HomePage from '../views/home/HomePage.vue'
import MainLayout from '../views/main/MainLayout.vue'
import MatrixRainDemo from '../views/demo/MatrixRainDemo.vue'

const routes = [
  {
    path: '/',
    name: 'home',
    component: HomePage
  },
  {
    path: '/main',
    name: 'main',
    component: MainLayout
  },
  {
    path: '/demo/matrix',
    name: 'matrix-rain-demo',
    component: MatrixRainDemo
  }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

export default router
