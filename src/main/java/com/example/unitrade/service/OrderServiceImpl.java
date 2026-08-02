package com.example.unitrade.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.unitrade.common.BusinessException;
import com.example.unitrade.config.JwtInterceptor;
import com.example.unitrade.dto.OrderCreateDTO;
import com.example.unitrade.entity.Order;
import com.example.unitrade.entity.Product;
import com.example.unitrade.entity.User;
import com.example.unitrade.mapper.OrderMapper;
import com.example.unitrade.mapper.ProductMapper;
import com.example.unitrade.mapper.UserMapper;
import com.example.unitrade.service.OrderService;
import com.example.unitrade.vo.OrderVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 订单服务实现类
 *
 * 交易流程（参考闲鱼）：
 * 1. 买家下单 → 待付款（商品锁定，30分钟不付款自动取消）
 * 2. 买家付款 → 已付款（商品正式售出，等待卖家发货）
 * 3. 卖家发货 → 已发货（等待买家收货确认）
 * 4. 买家收货 → 已完成（交易完成）
 *
 * 取消/退款：
 * - 待付款：买家可取消，卖家也可取消
 * - 已付款：买家可申请退款，卖家可取消（自动退款）
 * - 已发货：买家可申请退款
 * - 退款中：卖家可同意/拒绝退款
 * - 已取消/已退款：商品恢复在售
 *
 * 防一物多卖：
 * 下单时检查该商品是否有"进行中"的订单（待付款、已付款、已发货、退款中）
 * 如果有则拒绝下单
 */
@Service
@RequiredArgsConstructor
public class OrderServiceImpl implements OrderService {

    private final OrderMapper orderMapper;
    private final ProductMapper productMapper;
    private final UserMapper userMapper;

    /**
     * 买家下单
     *
     * 流程：
     * 1. 校验商品存在且在售
     * 2. 校验不能买自己的商品
     * 3. 校验无人正在下单该商品
     * 4. 事务中：插入订单 + 锁定商品
     */
    @Override
    @Transactional
    public OrderVO create(OrderCreateDTO dto) {
        Long buyerId = JwtInterceptor.getCurrentUserId();

        Product product = productMapper.selectById(dto.getProductId());
        if (product == null) {
            throw new BusinessException("商品不存在");
        }
        if (product.getStatus() != 1) {
            throw new BusinessException("商品已售出或已下架");
        }
        if (product.getUserId().equals(buyerId)) {
            throw new BusinessException("不能购买自己的商品");
        }

        // 检查该商品是否已有进行中的订单（防止一物多卖）
        Long count = orderMapper.selectCount(
                new LambdaQueryWrapper<Order>()
                        .eq(Order::getProductId, dto.getProductId())
                        .in(Order::getStatus, 1, 2, 3, 6) // 待付款、已付款、已发货、退款中
        );
        if (count > 0) {
            throw new BusinessException("该商品已被其他人下单");
        }

        // 插入订单（待付款）
        Order order = new Order();
        order.setBuyerId(buyerId);
        order.setSellerId(product.getUserId());
        order.setProductId(dto.getProductId());
        order.setStatus(1); // 待付款
        orderMapper.insert(order);

        // 锁定商品（不让别人再下单）
        product.setStatus(2); // 已锁定
        productMapper.updateById(product);

        return buildOrderVO(order);
    }

    /**
     * 买家付款
     */
    @Override
    @Transactional
    public void pay(Long orderId) {
        Long userId = JwtInterceptor.getCurrentUserId();

        Order order = orderMapper.selectById(orderId);
        if (order == null) {
            throw new BusinessException("订单不存在");
        }
        if (!order.getBuyerId().equals(userId)) {
            throw new BusinessException("只能支付自己的订单");
        }
        if (order.getStatus() != 1) {
            throw new BusinessException("订单状态不正确，无法付款");
        }

        order.setStatus(2); // 已付款
        order.setPayTime(LocalDateTime.now());
        orderMapper.updateById(order);

        // 商品正式标记为已售出
        Product product = productMapper.selectById(order.getProductId());
        if (product != null) {
            product.setStatus(3); // 已售出
            productMapper.updateById(product);
        }
    }

    /**
     * 卖家发货
     */
    @Override
    public void ship(Long orderId) {
        Long userId = JwtInterceptor.getCurrentUserId();

        Order order = orderMapper.selectById(orderId);
        if (order == null) {
            throw new BusinessException("订单不存在");
        }
        if (!order.getSellerId().equals(userId)) {
            throw new BusinessException("只能操作自己的订单");
        }
        if (order.getStatus() != 2) {
            throw new BusinessException("订单状态不正确，无法发货");
        }

        order.setStatus(3); // 已发货
        order.setShipTime(LocalDateTime.now());
        orderMapper.updateById(order);
    }

    /**
     * 买家确认收货
     */
    @Override
    public void confirmReceive(Long orderId) {
        Long userId = JwtInterceptor.getCurrentUserId();

        Order order = orderMapper.selectById(orderId);
        if (order == null) {
            throw new BusinessException("订单不存在");
        }
        if (!order.getBuyerId().equals(userId)) {
            throw new BusinessException("只能确认自己的订单");
        }
        if (order.getStatus() != 3) {
            throw new BusinessException("订单状态不正确，无法确认收货");
        }

        order.setStatus(4); // 已完成
        order.setCompleteTime(LocalDateTime.now());
        orderMapper.updateById(order);
    }

    /**
     * 取消订单
     *
     * 待付款：买家或卖家都可以取消
     * 已付款：只有卖家可以取消（如不想卖了），自动退款
     */
    @Override
    @Transactional
    public void cancel(Long orderId, String reason) {
        Long userId = JwtInterceptor.getCurrentUserId();
        Order order = orderMapper.selectById(orderId);

        if (order == null) {
            throw new BusinessException("订单不存在");
        }

        // 待付款：买家或卖家都可以取消
        if (order.getStatus() == 1) {
            if (!order.getBuyerId().equals(userId) && !order.getSellerId().equals(userId)) {
                throw new BusinessException("无权取消该订单");
            }
        }
        // 已付款：只有卖家可以取消
        else if (order.getStatus() == 2) {
            if (!order.getSellerId().equals(userId)) {
                throw new BusinessException("已付款的订单只能由卖家取消");
            }
        } else {
            throw new BusinessException("当前状态无法取消，请申请退款");
        }

        order.setStatus(5); // 已取消
        order.setCancelReason(reason);
        orderMapper.updateById(order);

        // 恢复商品为在售
        restoreProduct(order.getProductId());
    }

    /**
     * 买家申请退款
     * 仅已付款、已发货状态可申请
     */
    @Override
    public void requestRefund(Long orderId, String reason) {
        Long userId = JwtInterceptor.getCurrentUserId();

        Order order = orderMapper.selectById(orderId);
        if (order == null) {
            throw new BusinessException("订单不存在");
        }
        if (!order.getBuyerId().equals(userId)) {
            throw new BusinessException("只能申请退款自己的订单");
        }
        if (order.getStatus() != 2 && order.getStatus() != 3) {
            throw new BusinessException("当前状态无法申请退款");
        }

        order.setStatus(6); // 退款中
        order.setCancelReason(reason);
        orderMapper.updateById(order);
    }

    /**
     * 卖家同意退款
     */
    @Override
    @Transactional
    public void agreeRefund(Long orderId) {
        Long userId = JwtInterceptor.getCurrentUserId();

        Order order = orderMapper.selectById(orderId);
        if (order == null) {
            throw new BusinessException("订单不存在");
        }
        if (!order.getSellerId().equals(userId)) {
            throw new BusinessException("只能处理自己的订单");
        }
        if (order.getStatus() != 6) {
            throw new BusinessException("当前没有退款申请");
        }

        order.setStatus(7); // 已退款
        orderMapper.updateById(order);

        // 恢复商品为在售
        restoreProduct(order.getProductId());
    }

    /**
     * 卖家拒绝退款（订单回到原状态）
     */
    @Override
    public void rejectRefund(Long orderId) {
        Long userId = JwtInterceptor.getCurrentUserId();

        Order order = orderMapper.selectById(orderId);
        if (order == null) {
            throw new BusinessException("订单不存在");
        }
        if (!order.getSellerId().equals(userId)) {
            throw new BusinessException("只能处理自己的订单");
        }
        if (order.getStatus() != 6) {
            throw new BusinessException("当前没有退款申请");
        }

        // 回到已付款状态（简化处理，实际应回到申请前的状态）
        order.setStatus(2);
        order.setCancelReason(null);
        orderMapper.updateById(order);
    }

    @Override
    public Page<OrderVO> myBuyOrders(int page, int size) {
        Long userId = JwtInterceptor.getCurrentUserId();
        Page<Order> orderPage = orderMapper.selectPage(
                new Page<>(page, size),
                new LambdaQueryWrapper<Order>()
                        .eq(Order::getBuyerId, userId)
                        .orderByDesc(Order::getCreateTime)
        );
        return buildOrderVOPage(orderPage);
    }

    @Override
    public Page<OrderVO> mySellOrders(int page, int size) {
        Long userId = JwtInterceptor.getCurrentUserId();
        Page<Order> orderPage = orderMapper.selectPage(
                new Page<>(page, size),
                new LambdaQueryWrapper<Order>()
                        .eq(Order::getSellerId, userId)
                        .orderByDesc(Order::getCreateTime)
        );
        return buildOrderVOPage(orderPage);
    }

    /**
     * 恢复商品为在售状态
     */
    private void restoreProduct(Long productId) {
        Product product = productMapper.selectById(productId);
        if (product != null && product.getStatus() != 1) {
            product.setStatus(1);
            productMapper.updateById(product);
        }
    }

    /**
     * 将 Order 分页结果转为 OrderVO 分页结果
     */
    private Page<OrderVO> buildOrderVOPage(Page<Order> orderPage) {
        List<OrderVO> voList = orderPage.getRecords().stream()
                .map(this::buildOrderVO)
                .collect(Collectors.toList());
        Page<OrderVO> voPage = new Page<>(orderPage.getCurrent(), orderPage.getSize(), orderPage.getTotal());
        voPage.setRecords(voList);
        return voPage;
    }

    /**
     * 组装 OrderVO（补充商品、买卖家信息）
     */
    private OrderVO buildOrderVO(Order order) {
        Product product = productMapper.selectById(order.getProductId());
        User buyer = userMapper.selectById(order.getBuyerId());
        User seller = userMapper.selectById(order.getSellerId());

        OrderVO vo = new OrderVO();
        vo.setId(order.getId());
        vo.setBuyerId(order.getBuyerId());
        vo.setBuyerNickname(buyer != null ? buyer.getNickname() : "未知");
        vo.setBuyerPhone(buyer != null && order.getStatus() >= 2 ? buyer.getPhone() : "***");
        vo.setSellerId(order.getSellerId());
        vo.setSellerNickname(seller != null ? seller.getNickname() : "未知");
        vo.setSellerPhone(seller != null ? seller.getPhone() : "***");
        vo.setProductId(order.getProductId());
        vo.setProductTitle(product != null ? product.getTitle() : "已删除");
        vo.setProductPrice(product != null ? product.getPrice() : null);
        vo.setStatus(order.getStatus());
        vo.setStatusText(getStatusText(order.getStatus()));
        vo.setCancelReason(order.getCancelReason());
        vo.setCreateTime(order.getCreateTime());
        vo.setPayTime(order.getPayTime());
        vo.setShipTime(order.getShipTime());
        vo.setCompleteTime(order.getCompleteTime());

        if (product != null && StringUtils.hasText(product.getImages())) {
            vo.setProductCover(product.getImages().split(",")[0]);
        }

        return vo;
    }

    /**
     * 状态码转文字
     */
    private String getStatusText(Integer status) {
        switch (status) {
            case 1: return "待付款";
            case 2: return "已付款";
            case 3: return "已发货";
            case 4: return "已完成";
            case 5: return "已取消";
            case 6: return "退款中";
            case 7: return "已退款";
            default: return "未知";
        }
    }
}