package org.jeecg.modules.order.service;

import com.baomidou.mybatisplus.extension.service.IService;
import org.jeecg.modules.order.entity.BizOrder;
import org.jeecg.modules.order.entity.BizOrderItem;
import org.jeecg.modules.order.vo.BizOrderPage;

import java.io.Serializable;
import java.util.Collection;
import java.util.List;

/** 订单聚合接口，所有写操作同时处理主表和明细。 */
public interface IBizOrderService extends IService<BizOrder> {
    /**
     * 新建订单，编号、主键及金额由后端生成。
     * @param order 客户名称必填，状态缺省为待处理
     * @param items 至少一条有效明细
     */
    void saveMain(BizOrder order, List<BizOrderItem> items);

    /**
     * 更新已存在的订单，保留原编号并整体替换明细。
     * @param order 必须携带已存在的订单主键
     * @param items 至少一条有效明细
     */
    void updateMain(BizOrder order, List<BizOrderItem> items);

    /** 根据订单主键在同一事务中删除订单及明细。 */
    void delMain(String id);

    /** 批量删除订单及明细，任意失败时全部回滚。 */
    void delBatchMain(Collection<? extends Serializable> ids);

    /** 整个导入文件一次提交，任意订单无效时全部回滚。 */
    void importOrders(List<BizOrderPage> orders);
}

