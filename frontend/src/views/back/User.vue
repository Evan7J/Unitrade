<template>
  <div class="page">
    <h2>系统角色管理</h2>
    <el-input v-model="keyword" placeholder="搜索用户" style="width:200px;margin-right:10px" @keyup.enter="fetch" />
    <el-button @click="fetch">搜索</el-button>
    <el-table :data="list" style="margin-top:15px"><el-table-column prop="id" label="ID" width="60" /><el-table-column prop="nickname" label="昵称" /><el-table-column prop="phone" label="手机号" /><el-table-column label="角色" width="120"><template #default="{row}"><el-tag :type="row.role==='admin'?'danger':'info'">{{ row.role==='admin'?'管理员':'普通用户' }}</el-tag></template></el-table-column><el-table-column prop="createTime" label="注册时间" width="180" /><el-table-column label="操作" width="250"><template #default="{row}"><el-button size="small" @click="setRole(row.id,'admin')" v-if="row.role!=='admin'">设为管理员</el-button><el-button size="small" @click="setRole(row.id,'user')" v-if="row.role==='admin'">取消管理员</el-button><el-button size="small" type="danger" @click="doDel(row.id)">删除</el-button></template></el-table-column></el-table>
    <el-pagination v-model:current-page="page" :page-size="size" :total="total" @current-change="fetch" layout="total,prev,pager,next" />
  </div>
</template>
<script setup>
import { ref, onMounted } from 'vue'
import request from '../../utils/request'
import { ElMessage } from 'element-plus'
const list = ref([]); const keyword = ref(''); const page = ref(1); const size = ref(10); const total = ref(0)
const fetch = async () => { try { const res = await request.get('/admin/user/list', { params: { page: page.value, size: size.value, keyword: keyword.value } }); list.value = res.data.records; total.value = res.data.total } catch (e) {} }
const setRole = async (id, role) => { try { await request.put('/admin/user/role/' + id, null, { params: { role } }); ElMessage.success('ok'); fetch() } catch (e) {} }
const doDel = async (id) => { try { await request.delete('/admin/user/delete/' + id); ElMessage.success('ok'); fetch() } catch (e) {} }
onMounted(fetch)
</script>
<style scoped>.page { padding: 20px; } h2 { margin-bottom: 15px; }</style>