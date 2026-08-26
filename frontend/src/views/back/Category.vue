<template>
  <div class="page">
    <h2>商品分类管理</h2>
    <el-button type="primary" @click="openAdd">新增分类</el-button>
    <el-table :data="list" style="margin-top:15px"><el-table-column prop="id" label="ID" width="80" /><el-table-column prop="name" label="分类名称" /><el-table-column prop="sortOrder" label="排序" width="80" /><el-table-column label="操作" width="180"><template #default="{row}"><el-button size="small" @click="openEdit(row)">编辑</el-button><el-button size="small" type="danger" @click="doDel(row.id)">删除</el-button></template></el-table-column></el-table>
    <el-dialog :title="isEdit?'编辑':'新增'" v-model="vis"><el-form :model="f"><el-form-item label="名称"><el-input v-model="f.name" /></el-form-item><el-form-item label="排序"><el-input-number v-model="f.sortOrder" :min="0" /></el-form-item></el-form><template #footer><el-button @click="vis=false">取消</el-button><el-button type="primary" @click="doSave">保存</el-button></template></el-dialog>
  </div>
</template>
<script setup>
import { ref, onMounted } from 'vue'
import request from '../../utils/request'
import { ElMessage } from 'element-plus'
const list = ref([]); const vis = ref(false); const isEdit = ref(false); const f = ref({})
const openAdd = () => { f.value = { name: '', sortOrder: 0 }; isEdit.value = false; vis.value = true }
const openEdit = (r) => { f.value = { ...r }; isEdit.value = true; vis.value = true }
const fetch = async () => { try { list.value = (await request.get('/category/list')).data } catch (e) {} }
const doSave = async () => { try { if (isEdit.value) await request.put('/admin/category/update', f.value); else await request.post('/admin/category/save', f.value); ElMessage.success('ok'); vis.value = false; fetch() } catch (e) {} }
const doDel = async (id) => { try { await request.delete('/admin/category/delete/' + id); ElMessage.success('ok'); fetch() } catch (e) {} }
onMounted(fetch)
</script>
<style scoped>.page { padding: 20px; } h2 { margin-bottom: 15px; }</style>