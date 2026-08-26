<template>
  <div class="dashboard">
    <h2>后台首页</h2>
    <el-row :gutter="20">
      <el-col :span="6" v-for="s in stats" :key="s.label"><el-card><el-statistic :title="s.label" :value="s.value" /></el-card></el-col>
    </el-row>
  </div>
</template>
<script setup>
import { ref, onMounted } from 'vue'
import request from '../../utils/request'
const stats = ref([{label:'用户数',value:0},{label:'商品数',value:0},{label:'订单数',value:0},{label:'消息数',value:0}])
onMounted(async () => {
  try { const res = await request.get('/admin/dashboard/stats'); const d = res.data; stats.value[0].value = d.userCount; stats.value[1].value = d.productCount; stats.value[2].value = d.orderCount; stats.value[3].value = d.messageCount } catch (e) {}
})
</script>
<style scoped>
.dashboard { padding: 20px; }
h2 { margin-bottom: 20px; }
</style>