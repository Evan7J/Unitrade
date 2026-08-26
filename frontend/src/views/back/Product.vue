<template>
  <div class="page">
    <h2>闲置商品管理</h2>
    <el-input v-model="keyword" placeholder="搜索商品" style="width:200px;margin-right:10px" @keyup.enter="fetch" />
    <el-button @click="fetch">搜索</el-button>
    <el-table :data="list" style="margin-top:15px"><el-table-column prop="id" label="ID" width="60" /><el-table-column prop="title" label="标题" /><el-table-column prop="price" label="价格" width="100" /><el-table-column label="状态" width="100"><template #default="{row}">{{{1:'在售',2:'已锁定',3:'已下架'}[row.status]}}</template></el-table-column><el-table-column prop="createTime" label="时间" width="180" /><el-table-column label="操作" width="250"><template #default="{row}"><el-button v-if="row.status===3" size="small" type="success" @click="doOnline(row.id)">上架</el-button><el-button v-if="row.status===1" size="small" type="warning" @click="doOffline(row.id)">下架</el-button><el-button size="small" type="danger" @click="doDel(row.id)">删除</el-button></template></el-table-column></el-table>
    <el-pagination v-model:current-page="page" :page-size="size" :total="total" @current-change="fetch" layout="total,prev,pager,next" />
  </div>
</template>
<script setup>
import { ref, onMounted } from 'vue'
import request from '../../utils/request'
import { ElMessage } from 'element-plus'
const list = ref([]); const keyword = ref(''); const page = ref(1); const size = ref(10); const total = ref(0)
const fetch = async () => { try { const res = await request.get('/admin/product/list', { params: { page: page.value, size: size.value, keyword: keyword.value } }); list.value = res.data.records; total.value = res.data.total } catch (e) {} }
const doOffline = async (id) => { try { await request.put('/admin/product/offline/' + id); ElMessage.success('已下架'); fetch() } catch (e) {} }
const doOnline = async (id) => { try { await request.put('/admin/product/online/' + id); ElMessage.success('已上架'); fetch() } catch (e) {} }
const doDel = async (id) => { try { await request.delete('/admin/product/delete/' + id); ElMessage.success('删除成功'); fetch() } catch (e) {} }
onMounted(fetch)
</script>
<style scoped>.page { padding: 20px; } h2 { margin-bottom: 15px; }</style>