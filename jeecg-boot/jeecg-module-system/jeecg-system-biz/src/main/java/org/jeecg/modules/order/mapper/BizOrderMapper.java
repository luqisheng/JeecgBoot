package org.jeecg.modules.order.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.jeecg.modules.order.entity.BizOrder;

/** 订单持久化接口，修改和删除时先锁定主表以串行处理同一订单。 */
public interface BizOrderMapper extends BaseMapper<BizOrder> {
    @Select("SELECT * FROM biz_order WHERE id = #{id} FOR UPDATE")
    BizOrder selectForUpdate(@Param("id") String id);
}

