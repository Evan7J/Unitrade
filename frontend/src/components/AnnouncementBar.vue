<template>
  <!-- 公告通知条：多条公告上下叠加，每条可独立关闭 -->
  <div v-if="visibleList.length > 0" class="announce-wrapper">
    <div v-for="(item, i) in visibleList" :key="item.id || i" class="announce-bar">
      <div class="announce-inner">
        <el-icon :size="14"><Bell /></el-icon>
        <span class="announce-text">{{ item.title }}：{{ item.content }}</span>
        <el-icon :size="14" class="close-icon" @click="dismiss(i)"><Close /></el-icon>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { getList } from '../api/announcement'

const STORAGE_KEY = 'dismissed_announcements'

// 从 localStorage 读取已关闭的公告ID列表
const getDismissed = () => {
  try { return JSON.parse(localStorage.getItem(STORAGE_KEY) || '[]') } catch { return [] }
}

const visibleList = ref([])

onMounted(async () => {
  try {
    const res = await getList()
    const dismissed = getDismissed()
    // 过滤掉已被关闭的公告
    visibleList.value = (res.data || []).filter(item => !dismissed.includes(item.id))
  } catch {}
})

const dismiss = (i) => {
  const item = visibleList.value[i]
  if (item?.id) {
    const dismissed = getDismissed()
    dismissed.push(item.id)
    localStorage.setItem(STORAGE_KEY, JSON.stringify(dismissed))
  }
  visibleList.value.splice(i, 1)
}
</script>

<style scoped>
.announce-wrapper {
  margin-top: 60px; /* 补偿固定导航栏高度 */
}
.announce-bar {
  background: #FFF7E6; border-bottom: 1px solid #FFD591;
}
.announce-inner {
  max-width: 1200px; margin: 0 auto; padding: 7px 20px;
  display: flex; align-items: center; gap: 8px;
  font-size: 13px; color: #D46B08;
}
.announce-text { flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.close-icon { cursor: pointer; color: #D46B08; flex-shrink: 0; }
.close-icon:hover { color: #fa541c; }
</style>
