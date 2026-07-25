<script setup lang="ts">
import { ConfigProvider } from 'ant-design-vue'
import zhCN from 'ant-design-vue/es/locale/zh_CN'
import dayjs from 'dayjs'
import 'dayjs/locale/zh-cn'
dayjs.locale('zh-cn')
</script>

<template>
  <ConfigProvider :locale="zhCN" :theme="{ token: { colorPrimary: '#1677ff' } }">
    <RouterView />
  </ConfigProvider>
</template>
