package org.jeecg.modules.order.vo;

import lombok.Data;
import lombok.EqualsAndHashCode;
import org.jeecg.modules.order.entity.BizOrder;
import org.jeecg.modules.order.entity.BizOrderItem;
import org.jeecgframework.poi.excel.annotation.ExcelCollection;

import java.util.List;

/** 订单与明细的统一提交、Excel 导入导出数据结构。 */
@Data
@EqualsAndHashCode(callSuper = true)
public class BizOrderPage extends BizOrder {
    private static final long serialVersionUID = 1L;

    @ExcelCollection(name = "订单明细", orderNum = "10")
    private List<BizOrderItem> bizOrderItemList;
}

