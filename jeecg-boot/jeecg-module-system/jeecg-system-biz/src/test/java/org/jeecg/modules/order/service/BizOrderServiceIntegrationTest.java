package org.jeecg.modules.order.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import org.apache.ibatis.session.SqlSessionFactory;
import org.jeecg.config.mybatis.MybatisInterceptor;
import org.jeecg.modules.order.entity.BizOrder;
import org.jeecg.modules.order.entity.BizOrderItem;
import org.jeecg.modules.order.service.impl.BizOrderItemServiceImpl;
import org.jeecg.modules.order.service.impl.BizOrderServiceImpl;
import org.jeecg.modules.order.vo.BizOrderPage;
import org.jeecgframework.poi.excel.ExcelExportUtil;
import org.jeecgframework.poi.excel.ExcelImportUtil;
import org.jeecgframework.poi.excel.entity.ExportParams;
import org.jeecgframework.poi.excel.entity.ImportParams;
import org.jeecgframework.poi.excel.entity.enmus.ExcelType;
import org.apache.poi.ss.usermodel.Workbook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import javax.sql.DataSource;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.util.List;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.*;

/** 仅在独立的本地测试库运行，使用真实 MySQL 验证主子表事务和数据库约束。 */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = BizOrderServiceIntegrationTest.TestConfiguration.class)
@EnabledIfEnvironmentVariable(named = "JEECG_ORDER_TEST_URL",
        matches = "jdbc:mysql://(127\\.0\\.0\\.1|localhost):[0-9]+/codex_order_[a-zA-Z0-9_]+(\\?.*)?")
class BizOrderServiceIntegrationTest {
    @Autowired
    private IBizOrderService orderService;
    @Autowired
    private IBizOrderItemService itemService;
    @Autowired
    private DataSource dataSource;
    private JdbcTemplate jdbc;

    @BeforeEach
    void clearFixture() {
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("DROP TRIGGER IF EXISTS fail_order_item");
        jdbc.update("DELETE FROM biz_order_item");
        jdbc.update("DELETE FROM biz_order");
    }

    @AfterEach
    void removeFailureTrigger() {
        jdbc.execute("DROP TRIGGER IF EXISTS fail_order_item");
    }

    @Test
    void savesCalculatedAmountsAndServerOwnedIdentifiers() {
        BizOrder order = order().setId("submitted-id").setOrderNo("submitted-no").setTotalAmount(new BigDecimal("999.00"));
        BizOrderItem item = item("商品甲", 3, "19.90").setId("submitted-item").setOrderId("other-order");
        orderService.saveMain(order, List.of(item));
        BizOrder saved = orderService.getById(order.getId());
        assertNotEquals("submitted-id", saved.getId());
        assertNotEquals("submitted-no", saved.getOrderNo());
        assertTrue(saved.getOrderNo().startsWith("ORD"));
        assertEquals("pending", saved.getOrderStatus());
        assertEquals(new BigDecimal("59.70"), saved.getTotalAmount());
        assertNotNull(saved.getCreateTime());
        List<BizOrderItem> items = itemService.selectByMainId(saved.getId());
        assertEquals(1, items.size());
        assertEquals(saved.getId(), items.get(0).getOrderId());
        assertNotEquals("submitted-item", items.get(0).getId());
    }

    @Test
    void editsItemsAndPreservesOrderNumberAndCreationAudit() {
        BizOrder existing = order();
        orderService.saveMain(existing, List.of(item("旧商品", 1, "1.00")));
        BizOrder original = orderService.getById(existing.getId());
        BizOrder update = order().setId(existing.getId()).setOrderNo("tampered").setOrderStatus("processing");
        orderService.updateMain(update, List.of(item("新商品", 2, "12.50")));
        BizOrder saved = orderService.getById(existing.getId());
        assertEquals(original.getOrderNo(), saved.getOrderNo());
        assertEquals(original.getCreateTime(), saved.getCreateTime());
        assertEquals(new BigDecimal("25.00"), saved.getTotalAmount());
        assertEquals("processing", saved.getOrderStatus());
        assertEquals("新商品", itemService.selectByMainId(existing.getId()).get(0).getProductName());
    }

    @Test
    void rejectsInvalidOrdersBeforeWriting() {
        assertThrows(IllegalArgumentException.class, () -> orderService.saveMain(order(), List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> orderService.saveMain(order().setCustomerName(" "), List.of(item("商品", 1, "1.00"))));
        assertThrows(IllegalArgumentException.class,
                () -> orderService.saveMain(order().setOrderStatus("unknown"), List.of(item("商品", 1, "1.00"))));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM biz_order", Integer.class));
    }

    @Test
    void missingOrderCannotCreateOrphanItems() {
        assertThrows(IllegalArgumentException.class,
                () -> orderService.updateMain(order().setId("missing"), List.of(item("商品", 1, "1.00"))));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM biz_order_item", Integer.class));
    }

    @Test
    void rollsBackMainWhenItemInsertFails() {
        installFailureTrigger();
        assertThrows(RuntimeException.class,
                () -> orderService.saveMain(order(), List.of(item("正常商品", 1, "1.00"), item("失败商品", 1, "2.00"))));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM biz_order", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM biz_order_item", Integer.class));
    }

    @Test
    void rollsBackEditIncludingDeletedItemsWhenReplacementFails() {
        BizOrder existing = order();
        orderService.saveMain(existing, List.of(item("原商品", 1, "10.00")));
        installFailureTrigger();
        assertThrows(RuntimeException.class,
                () -> orderService.updateMain(order().setId(existing.getId()), List.of(item("失败商品", 2, "2.00"))));
        assertEquals(new BigDecimal("10.00"), orderService.getById(existing.getId()).getTotalAmount());
        assertEquals("原商品", itemService.selectByMainId(existing.getId()).get(0).getProductName());
    }

    @Test
    void deletesMainAndItemsTogetherIncludingBatchDelete() {
        BizOrder first = order();
        BizOrder second = order();
        orderService.saveMain(first, List.of(item("甲", 1, "1.00")));
        orderService.saveMain(second, List.of(item("乙", 1, "2.00")));
        orderService.delMain(first.getId());
        assertNull(orderService.getById(first.getId()));
        assertTrue(itemService.selectByMainId(first.getId()).isEmpty());
        orderService.delBatchMain(List.of(second.getId()));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM biz_order", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM biz_order_item", Integer.class));
    }

    @Test
    void databaseEnforcesUniqueOrderNumbers() {
        BizOrder first = order();
        BizOrder second = order();
        orderService.saveMain(first, List.of(item("甲", 1, "1.00")));
        orderService.saveMain(second, List.of(item("乙", 1, "1.00")));
        assertNotEquals(first.getOrderNo(), second.getOrderNo());
        assertThrows(RuntimeException.class,
                () -> jdbc.update("UPDATE biz_order SET order_no = ? WHERE id = ?", first.getOrderNo(), second.getId()));
    }

    @Test
    void rollsBackWholeImportFileWhenLaterOrderIsInvalid() {
        BizOrderPage valid = new BizOrderPage();
        valid.setCustomerName("有效客户");
        valid.setBizOrderItemList(List.of(item("有效商品", 1, "5.00")));
        BizOrderPage invalid = new BizOrderPage();
        invalid.setCustomerName("无效客户");
        invalid.setBizOrderItemList(List.of());
        assertThrows(IllegalArgumentException.class, () -> orderService.importOrders(List.of(valid, invalid)));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM biz_order", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM biz_order_item", Integer.class));
    }

    @Test
    void excelRoundTripPreservesMainFieldsAndMultipleItems() throws Exception {
        BizOrderPage page = new BizOrderPage();
        page.setCustomerName("导入客户");
        page.setOrderStatus("processing");
        page.setBizOrderItemList(List.of(item("商品甲", 3, "19.90"), item("商品乙", 2, "0.10")));
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (Workbook workbook = ExcelExportUtil.exportExcel(
                new ExportParams("订单管理", "测试", "订单", ExcelType.XSSF), BizOrderPage.class, new ArrayList<>(List.of(page)))) {
            workbook.write(bytes);
        }
        ImportParams params = new ImportParams();
        params.setTitleRows(2);
        params.setHeadRows(1);
        List<BizOrderPage> imported = ExcelImportUtil.importExcel(new ByteArrayInputStream(bytes.toByteArray()), BizOrderPage.class, params);
        assertEquals(1, imported.size());
        assertEquals("导入客户", imported.get(0).getCustomerName());
        assertEquals(2, imported.get(0).getBizOrderItemList().size());
        orderService.importOrders(imported);
        BizOrder saved = orderService.list().get(0);
        assertEquals("processing", saved.getOrderStatus());
        assertEquals(new BigDecimal("59.90"), saved.getTotalAmount());
        assertEquals(2, itemService.selectByMainId(saved.getId()).size());
    }

    private void installFailureTrigger() {
        jdbc.execute("CREATE TRIGGER fail_order_item BEFORE INSERT ON biz_order_item FOR EACH ROW "
                + "BEGIN IF NEW.product_name = '失败商品' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'forced item failure'; END IF; END");
    }

    private BizOrder order() {
        return new BizOrder().setCustomerName("测试客户");
    }

    private BizOrderItem item(String name, int quantity, String price) {
        return new BizOrderItem().setProductName(name).setQuantity(quantity).setUnitPrice(new BigDecimal(price));
    }

    @Configuration
    @EnableTransactionManagement
    @MapperScan("org.jeecg.modules.order.mapper")
    @Import({BizOrderServiceImpl.class, BizOrderItemServiceImpl.class})
    static class TestConfiguration {
        @Bean
        DataSource dataSource() {
            return new DriverManagerDataSource(System.getenv("JEECG_ORDER_TEST_URL"),
                    System.getenv().getOrDefault("JEECG_ORDER_TEST_USER", "root"), System.getenv("JEECG_ORDER_TEST_PASSWORD"));
        }

        @Bean
        SqlSessionFactory sqlSessionFactory(DataSource dataSource) throws Exception {
            MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
            factory.setDataSource(dataSource);
            MybatisConfiguration configuration = new MybatisConfiguration();
            configuration.setMapUnderscoreToCamelCase(true);
            factory.setConfiguration(configuration);
            factory.setPlugins(new MybatisInterceptor());
            factory.setMapperLocations(new PathMatchingResourcePatternResolver()
                    .getResources("classpath*:org/jeecg/modules/order/mapper/xml/*.xml"));
            return factory.getObject();
        }

        @Bean
        DataSourceTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }
    }
}
