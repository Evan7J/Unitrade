<template>
  <div class="page">
    <h2>聊天管理</h2>
    <el-table :data="list" style="margin-top:15px"><el-table-column prop="id" label="ID" width="60" /><el-table-column prop="senderId" label="发送者" width="80" /><el-table-column prop="receiverId" label="接收者" width="80" /><el-table-column prop="content" label="内容" /><el-table-column label="已读" width="80"><template #default="{row}">{{ row.isRead?'是':'否' }}</template></el-table-column><el-table-column prop="createTime" label="时间" width="180" /><el-table-column label="操作" width="100"><template #default="{row}"><el-button size="small" type="danger" @click="doDel(row.id)">删除</el-button></template></el-table-column></el-table>
    <el-pagination v-model:current-page="page" :page-size="size" :total="total" @current-change="fetch" layout="total,prev,pager,next" />
  </div>
</template>
<script setup>
import { ref, onMounted } from 'vue'
import request from '../../utils/request'
import { ElMessage } from 'element-plus'
const list = ref([]); const page = ref(1); const size = ref(10); const total = ref(0)
const fetch = async () => { try { const res = await request.get('/admin/chat/list', { params: { page: page.value, size: size.value } }); list.value = res.data.records; total.value = res.data.total } catch (e) {} }
const doDel = async (id) => { try { await request.delete('/admin/chat/delete/' + id); ElMessage.success('ok'); fetch() } catch (e) {} }
onMounted(fetch)
</script>
<style scoped>.page { padding: 20px; } h2 { margin-bottom: 15px; }</style>