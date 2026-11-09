package org.jeecg.modules.order.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.shiro.SecurityUtils;
import org.apache.shiro.authz.annotation.RequiresPermissions;
import org.jeecg.common.api.vo.Result;
import org.jeecg.common.aspect.annotation.AutoLog;
import org.jeecg.common.system.query.QueryGenerator;
import org.jeecg.common.system.vo.LoginUser;
import org.jeecg.modules.order.entity.BizOrder;
import org.jeecg.modules.order.entity.BizOrderItem;
import org.jeecg.modules.order.service.IBizOrderItemService;
import org.jeecg.modules.order.service.IBizOrderService;
import org.jeecg.modules.order.vo.BizOrderPage;
import org.jeecgframework.poi.excel.ExcelImportUtil;
import org.jeecgframework.poi.excel.def.NormalExcelConstants;
import org.jeecgframework.poi.excel.entity.ExportParams;
import org.jeecgframework.poi.excel.entity.ImportParams;
import org.jeecgframework.poi.excel.entity.enmus.ExcelType;
import org.jeecgframework.poi.excel.view.JeecgEntityExcelView;
import org.springframework.beans.BeanUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MultipartHttpServletRequest;
import org.springframework.web.servlet.ModelAndView;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** 订单管理接口，新增和编辑统一提交订单与明细。 */
@Slf4j
@Tag(name = "订单管理")
@RestController
@RequiredArgsConstructor
@RequestMapping("/order/bizOrder")
public class BizOrderController {
    private final IBizOrderService orderService;
    private final IBizOrderItemService itemService;

    /** 根据查询条件分页返回订单，支持编号、客户及状态筛选。 */
    @Operation(summary = "订单分页查询")
    @GetMapping("/list")
    public Result<IPage<BizOrder>> list(BizOrder order,
            @RequestParam(defaultValue = "1") Integer pageNo,
            @RequestParam(defaultValue = "10") Integer pageSize, HttpServletRequest request) {
        QueryWrapper<BizOrder> query = QueryGenerator.initQueryWrapper(order, request.getParameterMap());
        return Result.OK(orderService.page(new Page<>(pageNo, pageSize), query));
    }

    /** 新建订单，至少提交一条有效明细，忽略客户端编号和金额。 */
    @AutoLog(value = "订单管理-新增")
    @Operation(summary = "新增订单和明细")
    @RequiresPermissions("order:biz_order:add")
    @PostMapping("/add")
    public Result<String> add(@RequestBody BizOrderPage page) {
        BizOrder order = new BizOrder();
        BeanUtils.copyProperties(page, order);
        orderService.saveMain(order, page.getBizOrderItemList());
        return Result.OK("新增成功");
    }

    /** 编辑订单，必须携带原订单主键并提交完整的明细列表。 */
    @AutoLog(value = "订单管理-编辑")
    @Operation(summary = "编辑订单和明细")
    @RequiresPermissions("order:biz_order:edit")
    @RequestMapping(value = "/edit", method = {RequestMethod.PUT, RequestMethod.POST})
    public Result<String> edit(@RequestBody BizOrderPage page) {
        if (page.getId() == null || orderService.getById(page.getId()) == null) {
            return Result.error("未找到对应订单");
        }
        BizOrder order = new BizOrder();
        BeanUtils.copyProperties(page, order);
        orderService.updateMain(order, page.getBizOrderItemList());
        return Result.OK("编辑成功");
    }

    /** 根据订单主键删除主表及明细。 */
    @AutoLog(value = "订单管理-删除")
    @Operation(summary = "删除订单和明细")
    @RequiresPermissions("order:biz_order:delete")
    @DeleteMapping("/delete")
    public Result<String> delete(@RequestParam String id) {
        orderService.delMain(id);
        return Result.OK("删除成功");
    }

    /** 订单主键以逗号分隔，整个批次在同一事务中删除。 */
    @AutoLog(value = "订单管理-批量删除")
    @Operation(summary = "批量删除订单和明细")
    @RequiresPermissions("order:biz_order:deleteBatch")
    @DeleteMapping("/deleteBatch")
    public Result<String> deleteBatch(@RequestParam String ids) {
        orderService.delBatchMain(Arrays.asList(ids.split(",")));
        return Result.OK("批量删除成功");
    }

    /** 根据主键查询订单，不存在时返回业务错误。 */
    @Operation(summary = "查询订单详情")
    @GetMapping("/queryById")
    public Result<BizOrder> queryById(@RequestParam String id) {
        BizOrder order = orderService.getById(id);
        return order == null ? Result.error("未找到对应订单") : Result.OK(order);
    }

    /** 返回指定订单的完整明细，供编辑弹窗统一提交。 */
    @Operation(summary = "根据订单主键查询明细")
    @GetMapping("/queryBizOrderItemByMainId")
    public Result<List<BizOrderItem>> queryBizOrderItemByMainId(@RequestParam String id) {
        return Result.OK(itemService.selectByMainId(id));
    }

    /** 导出订单及其明细，支持当前筛选条件和选中订单。 */
    @Operation(summary = "导出订单和明细")
    @RequiresPermissions("order:biz_order:exportXls")
    @GetMapping("/exportXls")
    public ModelAndView exportXls(HttpServletRequest request, BizOrder order) {
        QueryWrapper<BizOrder> query = QueryGenerator.initQueryWrapper(order, request.getParameterMap());
        String selections = request.getParameter("selections");
        if (selections != null && !selections.isBlank()) {
            query.in("id", Arrays.asList(selections.split(",")));
        }
        List<BizOrderPage> pages = new ArrayList<>();
        for (BizOrder main : orderService.list(query)) {
            BizOrderPage page = new BizOrderPage();
            BeanUtils.copyProperties(main, page);
            page.setBizOrderItemList(itemService.selectByMainId(main.getId()));
            pages.add(page);
        }
        LoginUser user = (LoginUser) SecurityUtils.getSubject().getPrincipal();
        ModelAndView view = new ModelAndView(new JeecgEntityExcelView());
        view.addObject(NormalExcelConstants.FILE_NAME, "订单管理");
        view.addObject(NormalExcelConstants.CLASS, BizOrderPage.class);
        view.addObject(NormalExcelConstants.PARAMS, new ExportParams("订单管理", "导出人：" + user.getRealname(), "订单", ExcelType.XSSF));
        view.addObject(NormalExcelConstants.DATA_LIST, pages);
        return view;
    }

    /** 导入单个 Excel 文件，金额重新计算，任意订单失败时整份文件回滚。 */
    @AutoLog(value = "订单管理-导入")
    @Operation(summary = "导入订单和明细")
    @RequiresPermissions("order:biz_order:importExcel")
    @PostMapping("/importExcel")
    public Result<String> importExcel(HttpServletRequest request) {
        if (!(request instanceof MultipartHttpServletRequest multipartRequest)) {
            return Result.error("请上传Excel文件");
        }
        Map<String, MultipartFile> files = multipartRequest.getFileMap();
        if (files.size() != 1) {
            return Result.error("每次只能导入一个Excel文件");
        }
        MultipartFile file = files.values().iterator().next();
        ImportParams params = new ImportParams();
        params.setTitleRows(2);
        params.setHeadRows(1);
        try (InputStream input = file.getInputStream()) {
            List<BizOrderPage> orders = ExcelImportUtil.importExcel(input, BizOrderPage.class, params);
            orderService.importOrders(orders);
            return Result.OK("导入成功，订单数：" + orders.size());
        } catch (IllegalArgumentException exception) {
            return Result.error(exception.getMessage());
        } catch (Exception exception) {
            log.error("订单文件导入失败", exception);
            return Result.error("导入失败，请检查文件格式和订单明细");
        }
    }

    /** 将字段约束失败转换为统一的业务错误，供前端显示。 */
    @ExceptionHandler(IllegalArgumentException.class)
    public Result<String> handleInvalidOrder(IllegalArgumentException exception) {
        return Result.error(exception.getMessage());
    }
}
