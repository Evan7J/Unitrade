package com.example.unitrade.negotiation.pricing;

import com.example.unitrade.negotiation.domain.Money;

/**
 * 僵局检测 —— 分精度量化带来的必然后果，必须显式处理。
 *
 * <h2>为什么需要它</h2>
 * 数学上 {@code p(n)} 严格递增，报价严格递减。但报价量化到「分」之后，
 * 当每轮让价不足 1 分时，报价会停在同一个值上 ——
 * 属性测试实测：最小反例只需 <b>h = 1 分</b>，第 2 轮起就不动了。
 *
 * <p>这时"继续自动让步"已经没有任何意义（再算 1000 轮也是同一个数），
 * 系统会无限输出同一个价格。业务上必须识别出这个状态并<b>转人工</b>，
 * 而不是让会话空转。
 *
 * <p>这是"量化误差"从一个算术问题升级成业务规则的过程 ——
 * 也是为什么属性测试比样例测试值钱：几个手写样例根本测不到 1 分钱的抖动。
 *
 * <h2>它的定位</h2>
 * 这是<b>运行期的观测</b>，不是数学保证。数学保证是"报价永不上升"（解析可证），
 * 僵局是"报价不再下降"（有限精度下的可观测现象）。两者互补，不要互相替代。
 */
public final class StallDetector {

    private StallDetector() {
    }

    /**
     * 本轮报价相对上一轮是否已僵局（数值不再变化）。
     *
     * <p>注意：首轮没有"上一轮"，所以传 {@code null} 时恒返回 false。
     * 把首轮的特殊情形收进方法内部，调用方就不用自己判空 ——
     * 这类"边界由方法自己兜住"的设计能消掉一类低级 bug。
     */
    public static boolean isStalled(Money previousPrice, Money currentPrice) {
        if (previousPrice == null || currentPrice == null) {
            return false;
        }
        return previousPrice.fen() == currentPrice.fen();
    }

    /**
     * 报价是否"还在有效让价" —— {@link #isStalled} 的反面，语义更贴业务。
     * 保留两个方向的方法是为了让调用点的意图更难被误读：
     * {@code if (hasRoom(...))} 比 {@code if (!isStalled(...))} 少一次心智翻转。
     */
    public static boolean hasRoomToConcede(Money previousPrice, Money currentPrice) {
        return !isStalled(previousPrice, currentPrice);
    }
}
