package org.jeecg.modules.order.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonSetter;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;
import org.jeecgframework.poi.excel.annotation.Excel;
import org.springframework.format.annotation.DateTimeFormat;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Date;

/** 订单明细，金额通过后端明细计算保持一致。 */
@Data
@Accessors(chain = true)
@TableName("biz_order_item")
@Schema(description = "订单明细")
public class BizOrderItem implements Serializable {
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    @Schema(description = "主键")
    private String id;

    @Schema(description = "订单主键，由后端关联")
    private String orderId;

    @Excel(name = "商品", width = 25)
    @Schema(description = "商品名称")
    private String productName;

    @Excel(name = "数量", width = 15)
    @Schema(description = "数量，必须为正整数")
    private Integer quantity;

    @Excel(name = "单价", width = 20)
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    @Schema(description = "单价，非负且最多两位小数")
    private BigDecimal unitPrice;

    @Excel(name = "小计", width = 20)
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    @Schema(description = "明细小计，由数量乘以单价计算")
    private BigDecimal subtotalAmount;

    /**
     * JSON 数量先按十进制读取，再精确转换为整数。
     * 使用 Jackson 2/3 共有注解，禁止默认转换器静默截断小数。
     */
    @JsonSetter("quantity")
    public void readQuantity(BigDecimal quantityValue) {
        try {
            this.quantity = quantityValue == null ? null : quantityValue.intValueExact();
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("数量必须为整数且不能超过整数上限", exception);
        }
    }

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

