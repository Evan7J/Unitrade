package com.example.unitrade.search;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * RRF 融合的行为测试。
 *
 * <p>这些用例的重点不是"函数能跑"，而是把
 * <b>「k 在控制什么」「共识为什么能胜出」「排序为什么必须确定」</b>
 * 这三件事变成可执行的断言 —— 以后谁改了算法，会立刻知道自己破坏了什么。
 */
class RrfFuserTest {

    /**
     * 一组刻意构造的两路召回结果：
     * <pre>
     *   关键词路：[A, C, D, E, B]   → A 排第 1，B 排第 5
     *   向量路  ：[F, G, H, I, B]   → F 排第 1，B 排第 5
     * </pre>
     * A 和 F 都是"单路第一"，而 B 是唯一的"两路都出现（各第 5）"。
     */
    private static final List<List<String>> TWO_LISTS = List.of(
            List.of("A", "C", "D", "E", "B"),
            List.of("F", "G", "H", "I", "B"));

    @Test
    @DisplayName("k=60（默认）：两路共识的文档胜过只在单路排第一的文档")
    void 共识胜出() {
        List<String> fused = RrfFuser.fuse(TWO_LISTS);

        // B 得分 = 1/(60+5) + 1/(60+5) ≈ 0.0308
        // A 得分 = 1/(60+1)              ≈ 0.0164
        // 所以 B 必须排在 A 之前 —— 这正是 RRF 存在的理由：
        // 「两路都靠前」比「某一路的第一」更可信。
        assertThat(fused.indexOf("B"))
                .as("B（两路都第 5）应排在 A（关键词路第 1）之前")
                .isLessThan(fused.indexOf("A"));
    }

    @Test
    @DisplayName("k 的作用：k=1 时单路第一通吃，k=60 时共识反超（这条最能说明 k 在控制什么）")
    void k值控制头部差异的压平程度() {
        List<String> aggressive = RrfFuser.fuse(TWO_LISTS, 1);
        List<String> smoothed = RrfFuser.fuse(TWO_LISTS, RrfFuser.DEFAULT_K);

        // k=1：A 得 1/2 = 0.5，B 得 1/6+1/6 ≈ 0.333 → A 一票通吃
        assertThat(aggressive.get(0)).isEqualTo("A");
        assertThat(aggressive.indexOf("A"))
                .as("k=1 时单路第一（A）必须压过两路共识（B）")
                .isLessThan(aggressive.indexOf("B"));

        // k=60：B 反超（0.0308 > 0.0164），共识胜出
        assertThat(smoothed.get(0)).isEqualTo("B");
        assertThat(smoothed.indexOf("B"))
                .as("k=60 时两路共识（B）必须反超单路第一（A）")
                .isLessThan(smoothed.indexOf("A"));
    }

    @Test
    @DisplayName("完整融合顺序：按分数降序，同分按首次出现顺序打破平局")
    void 完整顺序与确定性平局() {
        List<String> fused = RrfFuser.fuse(TWO_LISTS);

        // 期望顺序推导：
        //   B = 2/65  = 0.030769  （两路共识，最高）
        //   A = 1/61  = 0.016393  ┐
        //   F = 1/61  = 0.016393  ┘ 同分 → A 先（它在第一路里先出现）
        //   C = 1/62  ┐
        //   G = 1/62  ┘ 同分 → C 先
        //   D = 1/63  ┐
        //   H = 1/63  ┘ 同分 → D 先
        //   E = 1/64  ┐
        //   I = 1/64  ┘ 同分 → E 先
        assertThat(fused).containsExactly("B", "A", "F", "C", "G", "D", "H", "E", "I");

        // 必须是"确定"的排序：同一份输入跑多次结果完全一致。
        // 没有这条保证，评测报告两次跑出来的顺序可能不同，就没法复现了。
        assertThat(RrfFuser.fuse(TWO_LISTS)).isEqualTo(fused);
    }

    @Test
    @DisplayName("同一路内部的重复文档只按第一次出现的位置算分")
    void 同路内重复只算一次() {
        List<String> withDuplicates = RrfFuser.fuse(List.of(
                List.of("A", "A", "A", "B"),
                List.of()));

        // A 只贡献 1/(60+1)，不因为重复了 3 次就变成 3 倍分
        assertThat(withDuplicates).containsExactly("A", "B");
    }

    @Test
    @DisplayName("排序确定：把两路的先后顺序对调，共识项的得分不变")
    void 路的顺序不影响共识项() {
        List<String> forward = RrfFuser.fuse(TWO_LISTS);
        List<String> reversedLists = RrfFuser.fuse(List.of(TWO_LISTS.get(1), TWO_LISTS.get(0)));

        // B 两路都是第 5，无论是哪一路先给出来，它都该是第一
        assertThat(forward.get(0)).isEqualTo("B");
        assertThat(reversedLists.get(0)).isEqualTo("B");
    }

    @Test
    @DisplayName("单路输入：保持原有顺序（这就是向量库不可用时的降级路径）")
    void 单路输入保持原顺序() {
        List<String> onlyKeyword = List.of("A", "B", "C");
        List<String> fused = RrfFuser.fuse(List.of(onlyKeyword, List.of()));

        assertThat(fused).containsExactly("A", "B", "C");
    }

    @Test
    @DisplayName("空输入与 null 安全")
    void 空输入安全() {
        assertThat(RrfFuser.fuse(null)).isEmpty();
        assertThat(RrfFuser.fuse(List.of())).isEmpty();
        assertThat(RrfFuser.fuse(List.of(List.of(), List.of()))).isEmpty();
        // 列表里混入 null 路也不该炸
        assertThat(RrfFuser.fuse(java.util.Arrays.asList(null, List.of("A")))).containsExactly("A");
    }

    @Test
    @DisplayName("k 必须 >= 1（k=0 会让 1/rank 在 rank 很大时依旧衰减，失去平滑意义）")
    void k值非法时快速失败() {
        assertThatThrownBy(() -> RrfFuser.fuse(TWO_LISTS, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("k 必须 >= 1");
    }

    @Test
    @DisplayName("得分公式就是 Σ 1/(k+rank)，不是近似")
    void 得分公式() {
        assertThat(RrfFuser.scoreOf(List.of(1), 60)).isEqualTo(1.0 / 61.0);
        assertThat(RrfFuser.scoreOf(List.of(5, 5), 60)).isEqualTo(2.0 / 65.0);
        assertThat(RrfFuser.scoreOf(List.of(), 60)).isZero();
    }

    @Test
    @DisplayName("融合是稳定排序：大量元素下不丢不重")
    void 不丢不重() {
        List<Integer> left = IntStream.range(0, 50).boxed().toList();
        List<Integer> right = IntStream.range(25, 75).boxed().toList();

        List<Integer> fused = RrfFuser.fuse(List.of(left, right));

        assertThat(fused).hasSize(75);            // 0..74
        assertThat(fused).doesNotHaveDuplicates();
        assertThat(fused).containsAll(left);
        assertThat(fused).containsAll(right);
    }
}
