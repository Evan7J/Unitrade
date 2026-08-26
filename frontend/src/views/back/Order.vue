<template>
  <div class="page">
    <h2>商品订单管理</h2>
    <el-select v-model="status" placeholder="筛选状态" clearable @change="fetch" style="width:150px;margin-right:10px">
      <el-option v-for="(v,k) in statusMap" :key="k" :label="v" :value="Number(k)" />
    </el-select>
    <el-table :data="list" style="margin-top:15px"><el-table-column prop="id" label="ID" width="60" /><el-table-column prop="buyerId" label="买家" width="80" /><el-table-column prop="sellerId" label="卖家" width="80" /><el-table-column prop="productId" label="商品" width="80" /><el-table-column label="状态" width="100"><template #default="{row}">{{ statusMap[row.status] }}</template></el-table-column><el-table-column prop="cancelReason" label="原因" /><el-table-column prop="createTime" label="时间" width="180" /><el-table-column label="操作" width="100"><template #default="{row}"><el-button size="small" type="danger" @click="doDel(row.id)">删除</el-button></template></el-table-column></el-table>
    <el-pagination v-model:current-page="page" :page-size="size" :total="total" @current-change="fetch" layout="total,prev,pager,next" />
  </div>
</template>
<script setup>
import { ref, onMounted } from 'vue'
import request from '../../utils/request'
import { ElMessage } from 'element-plus'
const statusMap = {1:'待付款',2:'已付款',3:'已发货',4:'已完成',5:'已取消',6:'退款中',7:'已退款'}
const list = ref([]); const status = ref(null); const page = ref(1); const size = ref(10); const total = ref(0)
const fetch = async () => { try { const p = { page: page.value, size: size.value }; if (status.value) p.status = status.value; const res = await request.get('/admin/order/list', { params: p }); list.value = res.data.records; total.value = res.data.total } catch (e) {} }
const doDel = async (id) => { try { await request.delete('/admin/order/delete/' + id); ElMessage.success('ok'); fetch() } catch (e) {} }
onMounted(fetch)
</script>
<style scoped>.page { padding: 20px; } h2 { margin-bottom: 15px; }</style>