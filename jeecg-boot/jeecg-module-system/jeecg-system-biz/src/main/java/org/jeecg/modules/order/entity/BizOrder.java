package org.jeecg.modules.order.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;
import org.jeecg.common.aspect.annotation.Dict;
import org.jeecgframework.poi.excel.annotation.Excel;
import org.springframework.format.annotation.DateTimeFormat;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Date;

/** 订单，金额通过后端明细计算保持一致。 */
@Data
@Accessors(chain = true)
@TableName("biz_order")
@Schema(description = "订单")
public class BizOrder implements Serializable {
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    @Schema(description = "主键")
    private String id;

    // 导入以首列识别订单；多条明细时合并主表列，避免被拆成多张订单。
    @Excel(name = "订单编号", width = 30, orderNum = "0", needMerge = true)
    @Schema(description = "订单编号，自动生成且不可修改")
    private String orderNo;

    @Excel(name = "客户", width = 25, orderNum = "1", needMerge = true)
    @Schema(description = "客户名称")
    private String customerName;

    @Excel(name = "金额", width = 20, orderNum = "2", needMerge = true)
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    @Schema(description = "订单金额，由明细小计汇总")
    private BigDecimal totalAmount;

    @Excel(name = "状态", width = 15, dicCode = "biz_order_status", orderNum = "3", needMerge = true)
    @Dict(dicCode = "biz_order_status")
    @Schema(description = "订单状态")
    private String orderStatus;

    /** 创建人，由框架根据登录用户填充。 */
    @Schema(description = "创建人")
    private String createBy;

    @JsonFormat(timezone = "GMT+8", pattern = "yyyy-MM-dd HH:mm:ss")
    @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    @Schema(description = "创建时间")
    private Date createTime;

    @Schema(description = "更新人")
    private String updateBy;

    @JsonFormat(timezone = "GMT+8", pattern = "yyyy-MM-dd HH:mm:ss")
    @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    @Schema(description = "更新时间")
    private Date updateTime;

    @Schema(description = "所属部门编码")
    private String sysOrgCode;
}

