package com.example.unitrade.negotiation.pricing;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

/**
 * 会话锚点扰动的派生器 —— 纯函数，零依赖，可独立单测。
 *
 * <h2>它解决什么问题</h2>
 * 如果首轮锚点是一个固定常数（历史实现里的 0.40），那么它就<b>公开可观测</b>，
 * 于是底价能被一行算式精确反解：
 * <pre>
 *   底价 = 挂牌价 - (挂牌价 - 首轮报价) / 0.40
 * </pre>
 * 把锚点做成"每次都不同的秘密值"之后，这条算式失效。
 *
 * <h2>三个必须同时成立的设计要求</h2>
 * <ol>
 *   <li><b>确定性</b>：同一个会话每次算出来必须一样。否则评测不可复现，
 *       并且买家刷新一次就能拿到不同的价 —— 那本身就是新漏洞。</li>
 *   <li><b>不可预测</b>：攻击者不能自己算出来。所以用 HMAC + <b>服务端密钥</b>，
 *       而不是 {@code productId % 100} 这类纯公开派生的方式 ——
 *       后者只要算法泄露就等于没有。</li>
 *   <li><b>按「商品 + 卖家」而不是按「会话」派生</b> —— 这一条最容易做错：
 *       如果按会话派生，攻击者只要<b>对同一件商品多开几个会话</b>，
 *       就会拿到同一个 h 的多组 (让出额, 锚点) 样本，
 *       取最大值就能把 h 反推出来，防护等于白做。
 *       按商品派生则重复观察不产生任何新信息。</li>
 * </ol>
 *
 * <h2>残余泄漏（诚实标注）</h2>
 * 扰动不是"消除"泄漏，而是把泄漏从「精确」降到「有界」：
 * 反解出的底价误差上界 = 可让空间 × 扰动幅度（默认 ±15%）。
 * 例如 h = ¥400 时约 ±¥60。要彻底消除只能不给报价 —— 那不叫议价了。
 */
public final class AnchorJitter {

    private static final String ALGORITHM = "HmacSHA256";

    private AnchorJitter() {
    }

    /**
     * 派生锚点扰动，单位 basis point。
     *
     * @param secret   服务端密钥。为空时仍然可用，但等同于"算法公开"，
     *                 生产环境必须通过环境变量配置真实密钥。
     * @param productId 商品 ID（null 会被转成字符串 "null"，测试里可用）
     * @param sellerId  卖家 ID
     * @param maxBp    扰动幅度上限。0 表示关闭扰动（只用于对照实验）
     * @return 落在 [-maxBp, +maxBp] 内的整数
     */
    public static int of(String secret, Object productId, Object sellerId, int maxBp) {
        if (maxBp <= 0) {
            return 0;
        }
        String seed = productId + ":" + sellerId;
        long digest = firstEightBytesAsLong(hmacSha256(secret == null ? "" : secret, seed));
        int span = 2 * maxBp + 1;                     // 闭区间 [−maxBp, +maxBp] 共 span 个取值
        return (int) Math.floorMod(digest, (long) span) - maxBp;
    }

    private static byte[] hmacSha256(String secret, String message) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM));
            return mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            // Mac/HMAC 在所有标准 JDK 上都存在，走到这里说明运行环境本身有问题
            throw new IllegalStateException("锚点扰动派生失败（HMAC-SHA256 不可用）", e);
        }
    }

    private static long firstEightBytesAsLong(byte[] bytes) {
        long value = 0L;
        for (int i = 0; i < Long.BYTES; i++) {
            value = (value << 8) | (bytes[i] & 0xFFL);
        }
        return value;
    }
}
