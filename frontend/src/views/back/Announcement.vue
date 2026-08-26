<template>
  <div class="page">
    <h2>公告管理</h2>
    <el-button type="primary" @click="dialogVisible=true;form={title:'',content:''};isEdit=false">新增公告</el-button>
    <el-table :data="list" style="margin-top:15px"><el-table-column prop="id" label="ID" width="80" /><el-table-column prop="title" label="标题" /><el-table-column prop="createTime" label="创建时间" width="180" /><el-table-column label="操作" width="180"><template #default="{row}"><el-button size="small" @click="form={...row};isEdit=true;dialogVisible=true">编辑</el-button><el-button size="small" type="danger" @click="doDel(row.id)">删除</el-button></template></el-table-column></el-table>
    <el-dialog :title="isEdit?'编辑公告':'新增公告'" v-model="dialogVisible"><el-form :model="form"><el-form-item label="标题"><el-input v-model="form.title" /></el-form-item><el-form-item label="内容"><el-input v-model="form.content" type="textarea" :rows="4" /></el-form-item></el-form><template #footer><el-button @click="dialogVisible=false">取消</el-button><el-button type="primary" @click="doSave">保存</el-button></template></el-dialog>
  </div>
</template>
<script setup>
import { ref, onMounted } from 'vue'
import request from '../../utils/request'
import { ElMessage } from 'element-plus'
const list = ref([])
const dialogVisible = ref(false)
const isEdit = ref(false)
const form = ref({})
const fetchData = async () => { try { list.value = (await request.get('/announcement/list')).data } catch (e) {} }
const doSave = async () => {
  try { if (isEdit.value) await request.put('/announcement/update', form.value); else await request.post('/announcement/save', form.value); ElMessage.success('保存成功'); dialogVisible.value = false; fetchData() } catch (e) {}
}
const doDel = async (id) => { try { await request.delete('/announcement/delete/' + id); ElMessage.success('删除成功'); fetchData() } catch (e) {} }
onMounted(fetchData)
</script>
<style scoped>.page { padding: 20px; } h2 { margin-bottom: 15px; }</style>