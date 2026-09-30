package com.example.unitrade.negotiation.vo;

import java.math.BigDecimal;

/**
 * 议价会话视图 —— 对外暴露的会话状态。
 *
 * <p><b>刻意没有底价、没有可让空间、没有 pMax。</b>
 * 这个 record 的字段集合就是它的泄漏面：它会进日志、进接口响应、进前端。
 * 只要字段不存在，怎么样都泄不出去。
 *
 * @param sessionNo    会话编号
 * @param productId    商品 ID
 * @param productTitle 商品标题
 * @param listPrice    挂牌价（买家本就看得到）
 * @param currentQuote 我方当前报价
 * @param roundNo      当前轮次
 * @param phase        会话状态机状态
 * @param phaseText    状态的中文展示
 * @param agreedPrice  成交价（未成交为 null）
 * @param orderId      成交后生成的订单 ID
 * @param guardCount   本会话被风控拦下的次数（可观测性）
 */
public record NegotiationSessionVO(
        String sessionNo,
        Long productId,
        String productTitle,
        BigDecimal listPrice,
        BigDecimal currentQuote,
        Integer roundNo,
        String phase,
        String phaseText,
        BigDecimal agreedPrice,
        Long orderId,
        Long guardCount) {
}
