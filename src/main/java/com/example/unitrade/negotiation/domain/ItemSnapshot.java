package com.example.unitrade.negotiation.domain;

/**
 * 商品事实快照 —— 「卖家以外的人也能看到」的那部分商品信息。
 *
 * <p><b>这个类型里没有 floorPrice，而且永远不该有。</b>
 *
 * <p>这不是随手的设计，是一条可执行的架构约定（见 {@code FloorPriceArchitectureTest}）。
 * 推理链条是：
 * <ol>
 *   <li>record 的 toString() 会自动打印所有字段；</li>
 *   <li>所以一个类型的字段集合 = 它能泄漏的信息集合 —— 日志、返回值、MQ 消息体都能带出去；</li>
 *   <li>所以只要 floorPrice 不出现在任何对外类型的字段里，
 *       它就不可能通过 {@code log.info("item={}", item)} 这类语句泄漏；</li>
 *   <li>所以底价只存在于 {@code pricing.AuthorizedRange} 一个地方，
 *       而那个类被架构测试锁在 pricing 包内。</li>
 * </ol>
 *
 * <p>注意这里出现的是 {@code listPrice}（挂牌价）而不是底价。
 * 挂牌价是买家本来就看得到的，放进类型里没有风险。
 *
 * @param daysListed 上架天数。刻意由外部传入而不是内部读系统时钟 ——
 *                   这保证了"同一输入必然同一输出"，是评测可复现的前提，
 *                   也顺带让整个内核天然线程安全。
 */
public record ItemSnapshot(
        String itemId,
        String title,
        String condition,
        Money listPrice,
        int daysListed) {

    public ItemSnapshot {
        if (itemId == null || itemId.isBlank()) {
            throw new IllegalArgumentException("itemId 不能为空");
        }
        if (listPrice == null || listPrice.fen() <= 0) {
            throw new IllegalArgumentException("挂牌价必须为正数，实际=" + listPrice);
        }
        if (daysListed < 0) {
            throw new IllegalArgumentException("上架天数不能为负，实际=" + daysListed);
        }
    }
}
