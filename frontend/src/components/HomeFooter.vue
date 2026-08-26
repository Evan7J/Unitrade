<template>
  <!--
    页面底部简易 Footer
    浅灰色低调展示：平台说明、交易须知、版权信息
  -->
  <footer class="bg-white border-t border-gray-100 mt-12">
    <div class="page-container py-10">
      <div class="grid grid-cols-1 sm:grid-cols-3 gap-8">
        <!-- 平台说明 -->
        <div>
          <h4 class="text-sm font-bold text-text-main mb-3">UniTrade 校园二手交易</h4>
          <p class="text-xs text-text-muted leading-relaxed">
            专为在校大学生打造的闲置物品交易平台。同校自提、安全可信，让闲置物品循环利用，助力绿色校园生活。
          </p>
        </div>

        <!-- 交易须知 -->
        <div>
          <h4 class="text-sm font-bold text-text-main mb-3">交易须知</h4>
          <ul class="text-xs text-text-muted space-y-1.5">
            <li>· 建议选择校内公共场所进行面交</li>
            <li>· 交易前仔细检查商品实物状况</li>
            <li>· 贵重物品建议保留交易凭证</li>
            <li>· 如遇纠纷可联系平台客服处理</li>
          </ul>
        </div>

        <!-- 联系方式 + 版权 -->
        <div>
          <h4 class="text-sm font-bold text-text-main mb-3">关于我们</h4>
          <p class="text-xs text-text-muted leading-relaxed mb-3">
            联系邮箱：hello@unitrade.demo<br />
            工作时间：周一至周五 9:00-18:00
          </p>
          <div class="text-xs text-text-muted">
            <p>&copy; {{ currentYear }} UniTrade. All Rights Reserved.</p>
            <p class="mt-1">校园闲置交易平台 · 个人学习项目</p>
          </div>
        </div>
      </div>
    </div>
  </footer>
</template>

<script setup>
const currentYear = new Date().getFullYear()
</script>
