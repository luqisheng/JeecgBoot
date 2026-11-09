package org.jeecg.modules.order.service;

import org.jeecg.modules.order.entity.BizOrderItem;
import org.jeecg.modules.order.entity.BizOrder;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证明细校验和金额计算，防止客户端篡改金额或数据库精度溢出。 */
class OrderAmountCalculatorTest {

    @Test
    void calculatesAmountsFromQuantityAndPriceIgnoringSubmittedSubtotals() {
        BizOrderItem first = item("商品甲", 3, "19.90").setSubtotalAmount(new BigDecimal("999.00"));
        BizOrderItem second = item("商品乙", 2, "0.10");

        assertEquals(new BigDecimal("59.90"), OrderAmountCalculator.calculate(List.of(first, second)));
        assertEquals(new BigDecimal("59.70"), first.getSubtotalAmount());
        assertEquals(new BigDecimal("0.20"), second.getSubtotalAmount());
    }

    @Test
    void permitsFreeProductsAndNormalizesMoneyScale() {
        BizOrderItem free = item("赠品", 1, "0");
        assertEquals(new BigDecimal("0.00"), OrderAmountCalculator.calculate(List.of(free)));
        assertEquals(new BigDecimal("0.00"), free.getUnitPrice());
    }

    @Test
    void requiresAtLeastOneItem() {
        assertThrows(IllegalArgumentException.class, () -> OrderAmountCalculator.calculate(null));
        assertThrows(IllegalArgumentException.class, () -> OrderAmountCalculator.calculate(List.of()));
        assertThrows(IllegalArgumentException.class, () -> OrderAmountCalculator.calculate(Arrays.asList((BizOrderItem) null)));
    }

    @Test
    void rejectsZeroNegativeAndMissingQuantities() {
        for (Integer quantity : Arrays.asList(0, -1, null)) {
            assertThrows(IllegalArgumentException.class, () -> OrderAmountCalculator.calculate(List.of(item("商品", quantity, "1.00"))));
        }
    }

    @Test
    void rejectsMissingNegativeAndOverPrecisePrices() {
        for (String price : Arrays.asList(null, "-0.01", "1.001", "10000000000000000.00")) {
            assertThrows(IllegalArgumentException.class, () -> OrderAmountCalculator.calculate(List.of(item("商品", 1, price))));
        }
    }

    @Test
    void rejectsMissingAndOverlongProductNames() {
        for (String name : Arrays.asList(null, " ", "商".repeat(201))) {
            assertThrows(IllegalArgumentException.class, () -> OrderAmountCalculator.calculate(List.of(item(name, 1, "1.00"))));
        }
    }

    @Test
    void rejectsSubtotalOverflow() {
        assertThrows(IllegalArgumentException.class,
                () -> OrderAmountCalculator.calculate(List.of(item("商品", 2, "9999999999999999.99"))));
    }

    @Test
    void rejectsTotalOverflow() {
        assertThrows(IllegalArgumentException.class, () -> OrderAmountCalculator.calculate(List.of(
                item("商品甲", 1, "9999999999999999.99"), item("商品乙", 1, "0.01"))));
    }

    @Test
    void rejectsFractionalJsonQuantityInsteadOfTruncatingIt() {
        ObjectMapper mapper = new ObjectMapper();
        assertThrows(JsonProcessingException.class,
                () -> mapper.readValue("{\"quantity\":1.5}", BizOrderItem.class));
    }

    @Test
    void rejectsJsonQuantityOutsideIntegerRange() {
        ObjectMapper mapper = new ObjectMapper();
        assertThrows(JsonProcessingException.class,
                () -> mapper.readValue("{\"quantity\":2147483648}", BizOrderItem.class));
    }

    @Test
    void jackson3RejectsFractionalQuantity() {
        tools.jackson.databind.json.JsonMapper mapper = tools.jackson.databind.json.JsonMapper.builder().build();
        assertThrows(tools.jackson.core.JacksonException.class,
                () -> mapper.readValue("{\"quantity\":1.5}", BizOrderItem.class));
    }

    @Test
    void bothJacksonVersionsPreserveValidIntegerQuantities() throws Exception {
        ObjectMapper jackson2 = new ObjectMapper();
        tools.jackson.databind.json.JsonMapper jackson3 = tools.jackson.databind.json.JsonMapper.builder().build();
        assertEquals(3, jackson2.readValue("{\"quantity\":3}", BizOrderItem.class).getQuantity());
        assertEquals(3, jackson3.readValue("{\"quantity\":3}", BizOrderItem.class).getQuantity());
    }

    @Test
    void jackson3RejectsOutOfRangeQuantity() {
        tools.jackson.databind.json.JsonMapper mapper = tools.jackson.databind.json.JsonMapper.builder().build();
        assertThrows(tools.jackson.core.JacksonException.class,
                () -> mapper.readValue("{\"quantity\":2147483648}", BizOrderItem.class));
    }

    @Test
    void bothJacksonVersionsSerializeMoneyAsExactStrings() throws Exception {
        BizOrder order = new BizOrder().setTotalAmount(new BigDecimal("9999999999999999.99"));
        BizOrderItem item = item("商品", 1, "9999999999999999.99").setSubtotalAmount(new BigDecimal("9999999999999999.99"));
        ObjectMapper jackson2 = new ObjectMapper();
        tools.jackson.databind.json.JsonMapper jackson3 = tools.jackson.databind.json.JsonMapper.builder().build();
        for (String json : List.of(jackson2.writeValueAsString(order), jackson3.writeValueAsString(order))) {
            assertTrue(jackson2.readTree(json).get("totalAmount").isTextual());
            assertEquals("9999999999999999.99", jackson2.readTree(json).get("totalAmount").asText());
        }
        for (String json : List.of(jackson2.writeValueAsString(item), jackson3.writeValueAsString(item))) {
            assertTrue(jackson2.readTree(json).get("unitPrice").isTextual());
            assertTrue(jackson2.readTree(json).get("subtotalAmount").isTextual());
        }
    }

    private BizOrderItem item(String productName, Integer quantity, String unitPrice) {
        return new BizOrderItem().setProductName(productName).setQuantity(quantity)
                .setUnitPrice(unitPrice == null ? null : new BigDecimal(unitPrice));
    }
}
