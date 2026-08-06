<script setup lang="ts">
defineProps<{ title?: string }>()
</script>

<template>
  <div class="auth-wrap">
    <!-- 左：品牌区 -->
    <div class="auth-brand">
      <div class="brand-inner">
        <img src="/favicon.svg" class="brand-logo" alt="Nexcompute 合算" />
        <h1>合算 Nexcompute</h1>
        <p class="brand-tagline">分布式 GPU 算力管理平台</p>
        <ul class="brand-features">
          <li>多物理实例统一调度与监控</li>
          <li>容器化计算环境一键部署</li>
          <li>镜像 / 存储池 / 课题组协同管理</li>
          <li>关键事件邮件提醒，离线不失联</li>
          <li>NAS集成管理，全流程覆盖</li>
        </ul>
        <div class="brand-footer">© Lab404金融大模型实验室</div>
      </div>
    </div>
    <!-- 右：表单区 -->
    <div class="auth-form">
      <div class="auth-form-inner">
        <div class="form-brand">
          <img src="/logo.svg" class="form-logo" alt="Nexcompute 合算" />
        </div>
        <h2 v-if="title" class="form-title">{{ title }}</h2>
        <slot />
      </div>
    </div>
  </div>
</template>

<style scoped>
.auth-wrap {
  min-height: 100vh;
  display: flex;
}
/* 左侧品牌区 */
.auth-brand {
  flex: 1.1;
  display: flex;
  align-items: center;
  justify-content: center;
  background: linear-gradient(135deg, #0958d9 0%, #1677ff 55%, #4096ff 100%);
  color: #fff;
  padding: 48px;
  position: relative;
  overflow: hidden;
}
.auth-brand::before {
  content: '';
  position: absolute;
  width: 420px;
  height: 420px;
  border-radius: 50%;
  background: rgba(255, 255, 255, 0.08);
  top: -120px;
  right: -120px;
}
.auth-brand::after {
  content: '';
  position: absolute;
  width: 300px;
  height: 300px;
  border-radius: 50%;
  background: rgba(255, 255, 255, 0.06);
  bottom: -100px;
  left: -80px;
}
.brand-inner {
  position: relative;
  z-index: 1;
  max-width: 420px;
}
.brand-logo {
  width: 80px;
  max-width: 80%;
  height: auto;
  filter: drop-shadow(0 4px 12px rgba(0, 0, 0, 0.2));
  margin-bottom: 24px;
}
.brand-inner h1 {
  font-size: 32px;
  font-weight: 800;
  margin: 0 0 8px;
  letter-spacing: 1px;
}
.brand-tagline {
  font-size: 16px;
  opacity: 0.9;
  margin: 0 0 32px;
}
.brand-features {
  list-style: none;
  padding: 0;
  margin: 0 0 40px;
}
.brand-features li {
  padding: 8px 0 8px 28px;
  position: relative;
  opacity: 0.95;
  font-size: 14px;
}
.brand-features li::before {
  content: '';
  position: absolute;
  left: 0;
  top: 14px;
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: #fff;
  opacity: 0.85;
}
.brand-footer {
  font-size: 12px;
  opacity: 0.7;
}
/* 右侧表单区 */
.auth-form {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  background: #f7f9fc;
  padding: 32px;
}
.auth-form-inner {
  width: 100%;
  max-width: 380px;
  background: #fff;
  border-radius: 16px;
  padding: 40px 36px;
  box-shadow: 0 10px 40px rgba(0, 0, 0, 0.08);
}
.form-brand {
  text-align: center;
  margin-bottom: 8px;
}
.form-logo {
  width: 180px;
  height: auto;
}
.form-title {
  text-align: center;
  font-size: 22px;
  font-weight: 700;
  color: #1f1f1f;
  margin: 8px 0 24px;
}
/* 窄屏：隐藏左侧品牌区，表单铺满 */
@media (max-width: 860px) {
  .auth-brand {
    display: none;
  }
  .auth-form {
    flex: 1;
  }
}
</style>
