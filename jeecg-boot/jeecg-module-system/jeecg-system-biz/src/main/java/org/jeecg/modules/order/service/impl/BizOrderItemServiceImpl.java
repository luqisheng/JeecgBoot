package org.jeecg.modules.order.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.jeecg.modules.order.entity.BizOrderItem;
import org.jeecg.modules.order.mapper.BizOrderItemMapper;
import org.jeecg.modules.order.service.IBizOrderItemService;
import org.springframework.stereotype.Service;

import java.util.List;

/** 查询订单明细，写入由订单聚合服务统一处理。 */
@Service
public class BizOrderItemServiceImpl extends ServiceImpl<BizOrderItemMapper, BizOrderItem> implements IBizOrderItemService {
    @Override
    public List<BizOrderItem> selectByMainId(String mainId) {
        return baseMapper.selectByMainId(mainId);
    }
}

