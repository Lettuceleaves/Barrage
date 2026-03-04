<template>
  <div class="demo-container">
    <!-- 背景动画 -->
    <MatrixRain
      text="Barrage Barrage Barrage Barrage Barrage Barrage"
      :fontSize="12"
      :speed="30"
    />

    <!-- 内容层 -->
    <div class="content">
      <div class="nav">
        <h2>Claude code 牛逼</h2>
      </div>

      <div class="hero-wrapper">
        <div class="title-wrapper">
          <h1 class="title">Claude code 牛逼</h1>
          <div class="light-wrapper">
            <div class="light"></div>
          </div>
        </div>

        <p class="subtitle">
          Claude code 牛逼 Claude code 牛逼 Claude code 牛逼
        </p>
      </div>

      <div class="button-wrapper">
        <button class="round-button" @click="handleRefresh">刷新页面</button>
        <button class="text-button">查看文档</button>
      </div>

      <div class="copyright">
        <p>© {{ currentYear }} Claude code 牛逼</p>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed } from 'vue'
import MatrixRain from '@/components/common/MatrixRain.vue'

const currentYear = computed(() => new Date().getFullYear())

const handleRefresh = () => {
  location.reload()
}
</script>

<style scoped>
.demo-container {
  position: relative;
  width: 100vw;
  height: 100vh;
  overflow: hidden;
}

.content {
  position: relative;
  z-index: 1;
  display: flex;
  flex-direction: column;
  justify-content: space-between;
  width: 100%;
  height: 100%;
  padding: 64px 0;
  background-color: rgba(0, 0, 0, 0.45);
}

.nav {
  width: 100%;
  padding-left: 10vw;
  color: white;
  text-align: start;
}

.nav h2 {
  font-size: 24px;
  font-weight: 600;
}

.hero-wrapper {
  display: flex;
  flex-direction: column;
  align-items: baseline;
  gap: 8px;
  padding-left: 10vw;
}

.title-wrapper {
  display: flex;
  gap: 12px;
  align-items: center;
}

.title {
  color: #F5F5F5;
  font-size: 48px;
  font-weight: 600;
  margin: 0;
}

.subtitle {
  color: rgba(255, 255, 255, 0.6);
  font-size: 20px;
  font-weight: 300;
  line-height: 28px;
  margin: 0;
}

.light-wrapper {
  display: flex;
  justify-content: center;
  align-items: center;
  border-radius: 50%;
  background-color: #006703;
  width: 20px;
  height: 20px;
}

.light {
  width: 70%;
  height: 70%;
  background: radial-gradient(
    circle,
    rgb(13, 255, 0) 20%,
    #00ff6e 80%,
    rgba(4, 255, 0, 0.2) 100%
  );
  border-radius: 50%;
  animation: breathing 0.5s infinite;
}

@keyframes breathing {
  0% {
    opacity: 0.2;
  }
  50% {
    opacity: 1;
  }
  100% {
    opacity: 0.2;
  }
}

.button-wrapper {
  display: flex;
  flex-direction: row;
  align-items: center;
  gap: 8px;
  padding-left: 10vw;
}

.round-button {
  padding: 10px 20px;
  font-size: 14px;
  font-weight: 600;
  line-height: 24px;
  border: none;
  border-radius: 13px;
  background-color: rgb(29, 29, 29);
  color: #f5f5f5;
  cursor: pointer;
  transition: background-color 0.2s cubic-bezier(0.25, 0.1, 0.25, 1);
}

.round-button:hover {
  background-color: #333333;
}

.round-button:active {
  background-color: #3e3e3e;
}

.text-button {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 16px;
  border-radius: 24px;
  background-color: transparent;
  color: rgba(255, 255, 255, 0.6);
  font-size: 14px;
  font-weight: 500;
  line-height: 20px;
  border: none;
  cursor: pointer;
}

.text-button:hover {
  color: rgba(255, 255, 255, 0.8);
}

.copyright {
  width: 100%;
  padding-left: 10vw;
  color: #f5f5f5;
  text-align: start;
  font-size: 14px;
  font-weight: 300;
  line-height: 20px;
}

@media screen and (max-width: 768px) {
  .title {
    font-size: 26px;
  }

  .subtitle {
    font-size: 14px;
  }

  .light-wrapper {
    width: 12px;
    height: 12px;
  }
}
</style>
