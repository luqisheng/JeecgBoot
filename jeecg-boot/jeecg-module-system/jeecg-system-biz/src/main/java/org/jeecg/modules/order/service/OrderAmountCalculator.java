package org.jeecg.modules.order.service;

import org.jeecg.modules.order.entity.BizOrderItem;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/** 统一校验明细并计算金额，防止客户端覆盖业务计算结果。 */
public final class OrderAmountCalculator {
    private static final BigDecimal MAX_AMOUNT = new BigDecimal("9999999999999999.99");
    private OrderAmountCalculator() {
    }

    /**
     * 校验明细并回填规范化单价和小计。
     * @param items 至少一条明细，数量为正整数、单价为非负金额
     * @return 两位小数的订单总金额
     */
    public static BigDecimal calculate(List<BizOrderItem> items) {
        if (items == null || items.isEmpty()) {
            throw new IllegalArgumentException("订单至少需要一条明细");
        }
        BigDecimal total = new BigDecimal("0.00");
        for (int index = 0; index < items.size(); index++) {
            BizOrderItem item = items.get(index);
            String prefix = "第" + (index + 1) + "条明细：";
            if (item == null) {
                throw new IllegalArgumentException(prefix + "明细不能为空");
            }
            if (item.getProductName() == null || item.getProductName().trim().isEmpty()
                    || item.getProductName().trim().length() > 200) {
                throw new IllegalArgumentException(prefix + "商品名称必填且不能超过200个字符");
            }
            if (item.getQuantity() == null || item.getQuantity() <= 0) {
                throw new IllegalArgumentException(prefix + "数量必须为正整数");
            }
            BigDecimal price = item.getUnitPrice();
            if (price == null || price.signum() < 0 || price.compareTo(MAX_AMOUNT) > 0) {
                throw new IllegalArgumentException(prefix + "单价必须为非负金额且不能超过金额上限");
            }
            try {
                price = price.setScale(2, RoundingMode.UNNECESSARY);
            } catch (ArithmeticException exception) {
                throw new IllegalArgumentException(prefix + "单价最多保留两位小数", exception);
            }
            BigDecimal subtotal = price.multiply(BigDecimal.valueOf(item.getQuantity()));
            if (subtotal.compareTo(MAX_AMOUNT) > 0) {
                throw new IllegalArgumentException(prefix + "小计超过金额上限");
            }
            total = total.add(subtotal);
            if (total.compareTo(MAX_AMOUNT) > 0) {
                throw new IllegalArgumentException("订单总金额超过金额上限");
            }
            item.setProductName(item.getProductName().trim());
            item.setUnitPrice(price);
            item.setSubtotalAmount(subtotal);
        }
        return total;
    }
}

