package com.example.unitrade.negotiation.dto;

import java.math.BigDecimal;

/**
 * 出价请求。
 *
 * @param offer     买家出价（元）
 * @param messageId <b>必填</b>：客户端消息 ID，用作幂等键。
 *                  前端应在用户点击"发送"时生成一次并复用它重试 ——
 *                  如果每次重试都换一个 messageId，幂等就形同虚设。
 * @param message   买家原话（用于话术生成；可为空）
 */
public record OfferRequest(BigDecimal offer, String messageId, String message) {
}
