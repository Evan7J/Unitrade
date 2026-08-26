<template>
  <div class="page">
    <h2>收货地址管理</h2>
    <el-input v-model="keyword" placeholder="收货人姓名" style="width:200px;margin-right:10px" @keyup.enter="fetch" />
    <el-button @click="fetch">搜索</el-button>
    <el-table :data="list" style="margin-top:15px"><el-table-column prop="id" label="ID" width="60" /><el-table-column prop="receiverName" label="收货人" /><el-table-column label="地区"><template #default="{row}">{{ row.province }}/{{ row.city }}/{{ row.district }}</template></el-table-column><el-table-column prop="detail" label="详细地址" /><el-table-column label="电话" width="120"><template #default="{row}">{{ row.phone?row.phone.substring(0,3)+'****':'' }}</template></el-table-column><el-table-column prop="userId" label="用户" width="80" /><el-table-column label="操作" width="100"><template #default="{row}"><el-button size="small" type="danger" @click="doDel(row.id)">删除</el-button></template></el-table-column></el-table>
    <el-pagination v-model:current-page="page" :page-size="size" :total="total" @current-change="fetch" layout="total,prev,pager,next" />
  </div>
</template>
<script setup>
import { ref, onMounted } from 'vue'
import request from '../../utils/request'
import { ElMessage } from 'element-plus'
const list = ref([]); const keyword = ref(''); const page = ref(1); const size = ref(10); const total = ref(0)
const fetch = async () => { try { const res = await request.get('/admin/address/list', { params: { page: page.value, size: size.value, keyword: keyword.value } }); list.value = res.data.records; total.value = res.data.total } catch (e) {} }
const doDel = async (id) => { try { await request.delete('/admin/address/delete/' + id); ElMessage.success('ok'); fetch() } catch (e) {} }
onMounted(fetch)
</script>
<style scoped>.page { padding: 20px; } h2 { margin-bottom: 15px; }</style>