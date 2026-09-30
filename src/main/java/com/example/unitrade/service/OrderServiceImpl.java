package com.example.unitrade.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
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
import org.springframework.scheduling.annotation.Scheduled;
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

    private static final int ORDER_TIMEOUT_MINUTES = 30;

    /**
     * 买家下单
     *
     * 流程：
     * 1. 校验商品存在且在售
     * 2. 校验不能买自己的商品
     * 3. 原子锁定商品状态，防止并发抢单
     * 4. 事务中：插入订单
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

        // 把商品从在售改成锁定，谁能改成功谁下单，并发下只有一个能成功
        int locked = productMapper.update(null, new LambdaUpdateWrapper<Product>()
                .eq(Product::getId, dto.getProductId())
                .eq(Product::getStatus, 1)
                .set(Product::getStatus, 2));
        if (locked == 0) {
            throw new BusinessException("该商品已被其他人下单");
        }

        Order order = new Order();
        order.setBuyerId(buyerId);
        order.setSellerId(product.getUserId());
        order.setProductId(dto.getProductId());
        // 价格快照：下单这一刻的商品价即是本次成交价。
        // 之后卖家再改价、或商品被删除，这笔订单的金额都不会跟着变。
        // （议价场景下 dealPrice 会被议价链路覆盖为谈成的价格）
        order.setOriginPrice(product.getPrice());
        order.setDealPrice(product.getPrice());
        order.setStatus(1); // 待付款
        orderMapper.insert(order);

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

        // 这里【不再】改商品状态，两重原因：
        //
        // 1）原来写的是 product.setStatus(3)，注释说"已售出"，
        //    但 Product 的定义里 3 是「已下架」——付款后商品会被标成"已下架"，
        //    语义错了，前端展示也跟着错。
        // 2）这个动作本身是多余的：create() 里的 CAS 更新已经把商品从
        //    「在售(1)」改成「已售出/交易中(2)」，商品在"待付款"期间就已不可被
        //    他人购买。付款只是订单状态的变化，商品的可售性在下单那一刻就已确定。
        //
        // 删掉它之后，"一物多卖"的防护完全不受影响（由 create() 的原子更新保证）。
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

        // 先记录申请退款前的真实状态，供「拒绝退款」时精确回退（见 rejectRefund）。
        // 少了这个字段，从「已发货(3)」申请的退款被拒绝后只能一律回到「已付款(2)」，
        // 卖家已经寄出的货就"没发过"了。
        order.setStatusBeforeRefund(order.getStatus());
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

        // 回到「申请退款之前」的状态，而不是一律回到「已付款」。
        // 原来这里写死 status=2 并在注释里承认是"简化处理"，后果是：
        // 从「已发货(3)」申请的退款被拒后，订单退回「已付款(2)」——
        // shipTime 还在（货确实发了），状态却显示"还没发货"，
        // 买卖双方看到的信息互相矛盾，客服也没法判断到底发没发。
        Integer restoreStatus = order.getStatusBeforeRefund();
        order.setStatus(restoreStatus != null ? restoreStatus : 2);
        order.setStatusBeforeRefund(null);
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

    @Scheduled(fixedDelay = 60000)
    @Transactional
    public void cancelTimeoutOrders() {
        LocalDateTime deadline = LocalDateTime.now().minusMinutes(ORDER_TIMEOUT_MINUTES);
        List<Order> orders = orderMapper.selectList(new LambdaQueryWrapper<Order>()
                .eq(Order::getStatus, 1)
                .lt(Order::getCreateTime, deadline));
        for (Order order : orders) {
            int rows = orderMapper.update(null, new LambdaUpdateWrapper<Order>()
                    .eq(Order::getId, order.getId())
                    .eq(Order::getStatus, 1)
                    .set(Order::getStatus, 5)
                    .set(Order::getCancelReason, "超时未支付，自动取消"));
            if (rows > 0) {
                restoreProduct(order.getProductId());
            }
        }
    }

    /**
     * 把「因本订单而被锁定」的商品恢复为在售。
     *
     * <p>条件从原来的 {@code ne(status, 1)} 收紧为 {@code eq(status, 2)}。
     * 原来的写法只要"不是在售"就恢复，于是「已下架(3)」的商品也会被一并恢复 ——
     * 卖家明明主动下架了，却因为别人下了个单又取消，商品"自己上架了"，
     * 等于系统覆盖了卖家的明确意图。
     *
     * <p>只有「已售出/交易中(2)」才是本订单造成的锁定，也只有它该被恢复。
     */
    private void restoreProduct(Long productId) {
        productMapper.update(null, new LambdaUpdateWrapper<Product>()
                .eq(Product::getId, productId)
                .eq(Product::getStatus, 2)
                .set(Product::getStatus, 1));
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
        // 金额取订单快照，不再实时查商品价。
        // 兜底到 product.getPrice() 只为兼容极早期、快照字段为空的历史数据。
        vo.setProductPrice(order.getDealPrice() != null
                ? order.getDealPrice()
                : (product != null ? product.getPrice() : null));
        vo.setOriginPrice(order.getOriginPrice());
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