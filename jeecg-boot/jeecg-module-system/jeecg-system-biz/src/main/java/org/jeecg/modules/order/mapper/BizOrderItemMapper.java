package org.jeecg.modules.order.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;
import org.jeecg.modules.order.entity.BizOrderItem;

import java.util.List;

/** 明细通过订单主键查询和删除，不提供独立修改入口。 */
public interface BizOrderItemMapper extends BaseMapper<BizOrderItem> {
    @Delete("DELETE FROM biz_order_item WHERE order_id = #{mainId}")
    int deleteByMainId(@Param("mainId") String mainId);

    List<BizOrderItem> selectByMainId(@Param("mainId") String mainId);
}

