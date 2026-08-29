<script setup>
// 应用壳：登录页全屏；业务页 = SideNav + TopBar + 内容区
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import SideNav from '@/components/SideNav.vue'
import TopBar from '@/components/TopBar.vue'
import ToastHost from '@/components/ToastHost.vue'
import ImportDialog from '@/components/ImportDialog.vue'

const route = useRoute()
const isLogin = computed(() => route.name === 'login')
</script>

<template>
  <div class="app" :class="{ 'is-login': isLogin }">
    <template v-if="!isLogin">
      <SideNav />
      <div class="main">
        <TopBar />
        <div class="page">
          <router-view />
        </div>
      </div>
      <ImportDialog />
    </template>
    <router-view v-else />
    <ToastHost />
  </div>
</template>
