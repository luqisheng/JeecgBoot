package org.jeecg.modules.order.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import lombok.RequiredArgsConstructor;
import org.jeecg.modules.order.entity.BizOrder;
import org.jeecg.modules.order.entity.BizOrderItem;
import org.jeecg.modules.order.mapper.BizOrderMapper;
import org.jeecg.modules.order.mapper.BizOrderItemMapper;
import org.jeecg.modules.order.service.IBizOrderService;
import org.jeecg.modules.order.service.OrderAmountCalculator;
import org.jeecg.modules.order.vo.BizOrderPage;
import org.springframework.stereotype.Service;
import org.springframework.beans.BeanUtils;
import org.springframework.transaction.annotation.Transactional;

import java.io.Serializable;
import java.util.Collection;
import java.util.List;
import java.util.Set;

/** 订单主子表聚合服务。 */
@Service
@RequiredArgsConstructor
public class BizOrderServiceImpl extends ServiceImpl<BizOrderMapper, BizOrder> implements IBizOrderService {
    private static final Set<String> ORDER_STATUSES = Set.of("pending", "processing", "completed", "cancelled");
    private final BizOrderItemMapper itemMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void saveMain(BizOrder order, List<BizOrderItem> items) {
        validateOrder(order);
        order.setTotalAmount(OrderAmountCalculator.calculate(items));
        // 不接受客户端的主键、编号及审计信息，避免覆盖既有数据或伪造创建人。
        order.setId(null).setOrderNo("ORD" + IdWorker.getIdStr());
        order.setCreateBy(null).setCreateTime(null).setUpdateBy(null).setUpdateTime(null).setSysOrgCode(null);
        baseMapper.insert(order);
        insertItems(order.getId(), items);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateMain(BizOrder order, List<BizOrderItem> items) {
        validateOrder(order);
        order.setTotalAmount(OrderAmountCalculator.calculate(items));
        BizOrder existing = requireOrder(order.getId());
        order.setOrderNo(existing.getOrderNo()).setCreateBy(existing.getCreateBy()).setCreateTime(existing.getCreateTime());
        order.setSysOrgCode(existing.getSysOrgCode()).setUpdateBy(null).setUpdateTime(null);
        baseMapper.updateById(order);
        // 主表行锁覆盖删除和重建，避免并发编辑产生两套明细。
        itemMapper.deleteByMainId(order.getId());
        insertItems(order.getId(), items);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delMain(String id) {
        requireOrder(id);
        itemMapper.deleteByMainId(id);
        baseMapper.deleteById(id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delBatchMain(Collection<? extends Serializable> ids) {
        if (ids == null || ids.isEmpty()) {
            throw new IllegalArgumentException("请选择要删除的订单");
        }
        // 固定锁顺序，降低并发批量删除的死锁风险。
        List<String> sortedIds = ids.stream().map(String::valueOf).distinct().sorted().toList();
        for (String id : sortedIds) {
            delMain(id);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void importOrders(List<BizOrderPage> orders) {
        if (orders == null || orders.isEmpty()) {
            throw new IllegalArgumentException("导入文件没有订单数据");
        }
        for (BizOrderPage page : orders) {
            if (page == null) {
                throw new IllegalArgumentException("导入订单不能为空");
            }
            BizOrder order = new BizOrder();
            BeanUtils.copyProperties(page, order);
            saveMain(order, page.getBizOrderItemList());
        }
    }

    private void validateOrder(BizOrder order) {
        if (order == null || order.getCustomerName() == null || order.getCustomerName().trim().isEmpty()
                || order.getCustomerName().trim().length() > 100) {
            throw new IllegalArgumentException("客户名称必填且不能超过100个字符");
        }
        order.setCustomerName(order.getCustomerName().trim());
        if (order.getOrderStatus() == null || order.getOrderStatus().isBlank()) {
            order.setOrderStatus("pending");
        }
        if (!ORDER_STATUSES.contains(order.getOrderStatus())) {
            throw new IllegalArgumentException("订单状态无效");
        }
    }

    private BizOrder requireOrder(String id) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("订单主键不能为空");
        }
        BizOrder order = baseMapper.selectForUpdate(id);
        if (order == null) {
            throw new IllegalArgumentException("未找到对应订单");
        }
        return order;
    }

    private void insertItems(String orderId, List<BizOrderItem> items) {
        for (BizOrderItem item : items) {
            item.setId(null).setOrderId(orderId);
            item.setCreateBy(null).setCreateTime(null).setUpdateBy(null).setUpdateTime(null).setSysOrgCode(null);
            itemMapper.insert(item);
        }
    }
}

