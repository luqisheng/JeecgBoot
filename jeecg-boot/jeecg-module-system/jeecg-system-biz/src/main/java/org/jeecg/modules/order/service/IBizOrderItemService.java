package org.jeecg.modules.order.service;

import com.baomidou.mybatisplus.extension.service.IService;
import org.jeecg.modules.order.entity.BizOrderItem;

import java.util.List;

/** 订单明细查询接口。 */
public interface IBizOrderItemService extends IService<BizOrderItem> {
    /** 根据订单主键返回明细，按生成主键稳定排序。 */
    List<BizOrderItem> selectByMainId(String mainId);
}

