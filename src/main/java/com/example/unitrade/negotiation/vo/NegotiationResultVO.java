package com.example.unitrade.negotiation.vo;

import java.math.BigDecimal;

/**
 * 单轮议价结果视图。
 *
 * @param sessionNo     会话编号
 * @param branch        本轮分支：ACCEPTED / COUNTERED / SUSPENDED / ESCALATED / REFUSED / STALLED
 *                      <p>其中 SUSPENDED 与 ESCALATED 都会让会话进入挂起、等卖家处理，
 *                      区别在原因：前者是<b>价格越界</b>（风控），后者是<b>买家情绪投诉</b>（体验）。
 *                      REFUSED 是"拒绝透露底价"，会话<b>不挂起</b>，买家可以接着说他想出的价。
 * @param quote         我方本轮报价（引擎算出来的）
 * @param agreedPrice   成交价（branch=ACCEPTED 时非空）
 * @param reply         Agent 话术（可能来自模板或模型，已通过数值一致性校验）
 * @param roundNo       本轮轮次
 * @param suspended     是否已挂起转人工
 * @param suspendReason 挂起原因的<b>展示文案</b>（不含任何价格下限信息）
 * @param toast         给前端的提示（如"已转人工，稍后会有卖家本人回复"）
 */
public record NegotiationResultVO(
        String sessionNo,
        String branch,
        BigDecimal quote,
        BigDecimal agreedPrice,
        String reply,
        Integer roundNo,
        Boolean suspended,
        String suspendReason,
        String toast) {

    public static NegotiationResultVO of(String sessionNo, String branch, BigDecimal quote,
                                         BigDecimal agreedPrice, String reply, int roundNo) {
        return new NegotiationResultVO(sessionNo, branch, quote, agreedPrice, reply, roundNo,
                false, null, null);
    }
}
