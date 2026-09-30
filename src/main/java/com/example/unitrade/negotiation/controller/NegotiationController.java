package com.example.unitrade.negotiation.controller;

import com.example.unitrade.common.BusinessException;
import com.example.unitrade.common.Result;
import com.example.unitrade.entity.NegotiationRound;
import com.example.unitrade.negotiation.dto.OfferRequest;
import com.example.unitrade.negotiation.service.NegotiationService;
import com.example.unitrade.negotiation.vo.CostSummaryVO;
import com.example.unitrade.negotiation.vo.NegotiationResultVO;
import com.example.unitrade.negotiation.vo.NegotiationSessionVO;
import lombok.RequiredArgsConstructor;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 议价接口。
 *
 * <p>路径统一在 {@code /api/negotiation} 下 —— 需要登录（WebConfig 里未排除该前缀），
 * 因为议价必然涉及"我是谁"（买家身份）。
 */
@RestController
@RequestMapping("/api/negotiation")
@RequiredArgsConstructor
public class NegotiationController {

    private final NegotiationService negotiationService;

    /**
     * 卖家为商品配置议价底价。
     * PUT /api/negotiation/authorization/{productId}  Body: {"floorPrice": 600.00}
     */
    @PutMapping("/authorization/{productId}")
    public Result<?> configureAuthorization(@PathVariable Long productId,
                                            @RequestBody Map<String, Object> body) {
        Object raw = body.get("floorPrice");
        if (raw == null) {
            throw new BusinessException("请填写底价");
        }
        BigDecimal floorPrice;
        try {
            floorPrice = new BigDecimal(String.valueOf(raw));
        } catch (NumberFormatException e) {
            throw new BusinessException("底价格式不正确");
        }
        negotiationService.configureAuthorization(productId, floorPrice);
        return Result.success("底价已保存，之后的新会话按这个价格授权");
    }

    /**
     * 买家发起议价。
     * POST /api/negotiation/start/{productId}
     */
    @PostMapping("/start/{productId}")
    public Result<NegotiationResultVO> start(@PathVariable Long productId) {
        return Result.success(negotiationService.start(productId));
    }

    /**
     * 买家出价。
     * POST /api/negotiation/{sessionNo}/offer
     * Body: {"offer": 700.00, "messageId": "前端生成的唯一ID", "message": "700 卖不卖"}
     */
    @PostMapping("/{sessionNo}/offer")
    public Result<NegotiationResultVO> offer(@PathVariable String sessionNo,
                                             @RequestBody OfferRequest request) {
        if (!StringUtils.hasText(request.messageId())) {
            // 没有幂等键就不该接受出价：无法区分"重试"和"两次真实出价"
            throw new BusinessException("缺少 messageId（幂等键）");
        }
        return Result.success(negotiationService.offer(
                sessionNo, request.offer(), request.messageId(), request.message()));
    }

    /**
     * 买家接受当前报价。
     * POST /api/negotiation/{sessionNo}/accept  Body: {"messageId": "..."}
     */
    @PostMapping("/{sessionNo}/accept")
    public Result<NegotiationResultVO> accept(@PathVariable String sessionNo,
                                              @RequestBody Map<String, String> body) {
        String messageId = body == null ? null : body.get("messageId");
        if (!StringUtils.hasText(messageId)) {
            throw new BusinessException("缺少 messageId（幂等键）");
        }
        return Result.success(negotiationService.accept(sessionNo, messageId));
    }

    /**
     * 卖家处理人工接管（HITL 的收口）。
     *
     * POST /api/negotiation/{sessionNo}/human-decision
     * Body: {"approve": true, "price": 550.00, "messageId": "..."}
     *
     * <p>{@code approve=false} 表示拒绝，买家看到"卖家不接受"；
     * {@code approve=true} 表示放行，{@code price} 可省略（省略则用触发挂起的那次买家出价）。
     * 放行价<b>允许低于底价</b>——决定权在卖家本人，但会留痕。
     *
     * <p>只有该商品的卖家可调用，且必须带 messageId（幂等键，与出价同一套防重）。
     */
    @PostMapping("/{sessionNo}/human-decision")
    public Result<NegotiationResultVO> humanDecision(@PathVariable String sessionNo,
                                                     @RequestBody Map<String, Object> body) {
        if (body == null || body.get("approve") == null) {
            throw new BusinessException("缺少 approve 字段");
        }
        boolean approve = Boolean.parseBoolean(String.valueOf(body.get("approve")));

        BigDecimal price = null;
        Object raw = body.get("price");
        if (raw != null && StringUtils.hasText(String.valueOf(raw))) {
            try {
                price = new BigDecimal(String.valueOf(raw));
            } catch (NumberFormatException e) {
                throw new BusinessException("price 格式不正确");
            }
        }

        String messageId = body.get("messageId") == null ? null : String.valueOf(body.get("messageId"));
        if (!StringUtils.hasText(messageId)) {
            throw new BusinessException("缺少 messageId（幂等键）");
        }
        return Result.success(negotiationService.humanDecision(sessionNo, approve, price, messageId));
    }

    /**
     * 查询会话状态。
     * GET /api/negotiation/{sessionNo}
     */
    @GetMapping("/{sessionNo}")
    public Result<NegotiationSessionVO> state(@PathVariable String sessionNo) {
        return Result.success(negotiationService.sessionState(sessionNo));
    }

    /**
     * 成本汇总报表。
     * GET /api/negotiation/cost/summary
     *
     * <p>返回的是<b>全库聚合</b>值（不含单个用户数据），所以登录即可查看。
     * 存在的意义：「单次议价 token 成本」这个数字随时能被重新算一遍 ——
     * 能由数据重算的才叫可复现，写死在 README 里的只是个说法。
     */
    @GetMapping("/cost/summary")
    public Result<CostSummaryVO> costSummary() {
        return Result.success(negotiationService.costSummary());
    }

    /**
     * 查询轮次历史（可追溯的议价记录）。
     * GET /api/negotiation/{sessionNo}/rounds
     */
    @GetMapping("/{sessionNo}/rounds")
    public Result<List<NegotiationRound>> rounds(@PathVariable String sessionNo) {
        return Result.success(negotiationService.rounds(sessionNo));
    }
}
