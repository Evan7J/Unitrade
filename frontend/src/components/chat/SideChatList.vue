<template>
  <!--
    SideChatList - 左侧会话侧边栏
    展示会话列表：对方头像+昵称、商品小图、最新消息预览、时间、红色未读角标
    顶部搜索框筛选会话
  -->
  <div class="side-chat-list">
    <!-- 顶部标题 + 搜索 -->
    <div class="side-header">
      <h3 class="side-title">消息</h3>
      <el-input
        v-model="searchKey"
        size="small"
        placeholder="搜索会话..."
        :prefix-icon="Search"
        clearable
        class="search-input"
      />
    </div>

    <!-- 会话列表 -->
    <div class="conv-list" v-if="filteredList.length > 0">
      <div
        v-for="conv in filteredList"
        :key="conv.userId"
        class="conv-item"
        :class="{ active: activeUserId === conv.userId }"
        @click="$emit('select', conv)"
      >
        <!-- 对方头像 -->
        <div class="conv-avatar-wrap">
          <el-avatar :size="44" :src="conv.avatarUrl">
            <el-icon :size="20"><User /></el-icon>
          </el-avatar>
          <!-- 未读红点 -->
          <span v-if="conv.unreadCount > 0" class="unread-dot">{{ conv.unreadCount > 99 ? '99+' : conv.unreadCount }}</span>
        </div>

        <!-- 会话信息 -->
        <div class="conv-info">
          <div class="conv-top-row">
            <span class="conv-name">{{ conv.nickname }}</span>
            <span class="conv-time">{{ formatConvTime(conv.lastTime) }}</span>
          </div>
          <p class="conv-preview">{{ conv.lastMessage || '暂无消息' }}</p>
          <!-- 商品价格（如有） -->
          <p v-if="conv.productPrice" class="conv-product-price">¥{{ conv.productPrice }}</p>
        </div>

        <!-- 商品缩略图（如有） -->
        <div class="conv-product-thumb" v-if="conv.productCover">
          <img :src="conv.productCover" @error="e => e.target.style.display = 'none'" />
        </div>
      </div>
    </div>

    <!-- 空状态 -->
    <el-empty v-else description="暂无会话" :image-size="64" />
  </div>
</template>

<script setup>
import { ref, computed } from 'vue'
import { Search } from '@element-plus/icons-vue'

const props = defineProps({
  conversations: { type: Array, default: () => [] },
  activeUserId: { type: [Number, String], default: null },
})

defineEmits(['select'])

const searchKey = ref('')

// 搜索过滤
const filteredList = computed(() => {
  if (!searchKey.value.trim()) return props.conversations
  const kw = searchKey.value.trim().toLowerCase()
  return props.conversations.filter(c =>
    c.nickname?.toLowerCase().includes(kw) ||
    c.lastMessage?.toLowerCase().includes(kw)
  )
})

// 会话时间格式化
const formatConvTime = (t) => {
  if (!t) return ''
  const d = new Date(t)
  const now = new Date()
  const pad = (n) => String(n).padStart(2, '0')
  if (d.toDateString() === now.toDateString()) {
    return `${pad(d.getHours())}:${pad(d.getMinutes())}`
  }
  return `${d.getMonth() + 1}/${d.getDate()}`
}
</script>

<style scoped>
.side-chat-list {
  width: 320px;
  height: 100%;
  display: flex;
  flex-direction: column;
  background: #fff;
  border-right: 1px solid #eee;
  flex-shrink: 0;
}

/* 头部 */
.side-header {
  padding: 16px;
  border-bottom: 1px solid #f0f0f0;
  flex-shrink: 0;
}

.side-title {
  font-size: 17px;
  font-weight: 700;
  margin-bottom: 10px;
}

.search-input :deep(.el-input__wrapper) {
  border-radius: 20px;
  background: #f5f5f5;
}

/* 会话列表 */
.conv-list {
  flex: 1;
  overflow-y: auto;
}

.conv-item {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 14px 16px;
  cursor: pointer;
  transition: background 0.15s;
}
.conv-item:hover { background: #fafafa; }
.conv-item.active { background: #FFF0E0; }

/* 头像 + 红点 */
.conv-avatar-wrap {
  position: relative;
  flex-shrink: 0;
}

.unread-dot {
  position: absolute;
  top: -4px;
  right: -4px;
  background: #f56c6c;
  color: #fff;
  font-size: 10px;
  min-width: 18px;
  height: 18px;
  line-height: 18px;
  text-align: center;
  border-radius: 9px;
  padding: 0 5px;
}

/* 会话信息 */
.conv-info {
  flex: 1;
  min-width: 0;
}

.conv-top-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 2px;
}

.conv-name {
  font-weight: 600;
  font-size: 14px;
  color: #333;
}

.conv-time {
  font-size: 11px;
  color: #bbb;
  flex-shrink: 0;
}

.conv-preview {
  font-size: 12px;
  color: #999;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.conv-product-price {
  font-size: 12px;
  color: #FF7D00;
  font-weight: 600;
  margin-top: 2px;
}

/* 商品缩略图 */
.conv-product-thumb {
  width: 40px;
  height: 40px;
  border-radius: 6px;
  overflow: hidden;
  background: #f0f0f0;
  flex-shrink: 0;
}
.conv-product-thumb img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}
</style>
