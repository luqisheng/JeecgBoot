-- 订单管理主子表；允许本地初始化后由 Flyway 再执行同一脚本。
CREATE TABLE IF NOT EXISTS biz_order (
    id varchar(36) NOT NULL COMMENT '主键',
    order_no varchar(50) NOT NULL COMMENT '订单编号',
    customer_name varchar(100) NOT NULL COMMENT '客户名称',
    total_amount decimal(18,2) NOT NULL COMMENT '订单总金额',
    order_status varchar(20) NOT NULL DEFAULT 'pending' COMMENT '订单状态',
    create_by varchar(50) DEFAULT NULL COMMENT '创建人',
    create_time datetime DEFAULT NULL COMMENT '创建时间',
    update_by varchar(50) DEFAULT NULL COMMENT '更新人',
    update_time datetime DEFAULT NULL COMMENT '更新时间',
    sys_org_code varchar(64) DEFAULT NULL COMMENT '所属部门编码',
    PRIMARY KEY (id),
    UNIQUE KEY uk_biz_order_no (order_no),
    KEY idx_biz_order_customer (customer_name),
    KEY idx_biz_order_status (order_status),
    CONSTRAINT chk_biz_order_amount CHECK (total_amount >= 0),
    CONSTRAINT chk_biz_order_status CHECK (order_status IN ('pending', 'processing', 'completed', 'cancelled'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='订单';

CREATE TABLE IF NOT EXISTS biz_order_item (
    id varchar(36) NOT NULL COMMENT '主键',
    order_id varchar(36) NOT NULL COMMENT '订单主键',
    product_name varchar(200) NOT NULL COMMENT '商品名称',
    quantity int NOT NULL COMMENT '数量',
    unit_price decimal(18,2) NOT NULL COMMENT '单价',
    subtotal_amount decimal(18,2) NOT NULL COMMENT '明细小计',
    create_by varchar(50) DEFAULT NULL COMMENT '创建人',
    create_time datetime DEFAULT NULL COMMENT '创建时间',
    update_by varchar(50) DEFAULT NULL COMMENT '更新人',
    update_time datetime DEFAULT NULL COMMENT '更新时间',
    sys_org_code varchar(64) DEFAULT NULL COMMENT '所属部门编码',
    PRIMARY KEY (id),
    KEY idx_biz_order_item_order (order_id),
    CONSTRAINT fk_biz_order_item_order FOREIGN KEY (order_id) REFERENCES biz_order (id),
    CONSTRAINT chk_biz_order_item_quantity CHECK (quantity > 0),
    CONSTRAINT chk_biz_order_item_price CHECK (unit_price >= 0),
    CONSTRAINT chk_biz_order_item_subtotal CHECK (subtotal_amount >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='订单明细';

-- 订单字典随迁移发布，避免各环境下拉选项缺失。
INSERT INTO sys_dict (id, dict_name, dict_code, description, del_flag, create_by, create_time, type, tenant_id)
SELECT '179420787753320', '订单状态', 'biz_order_status', '订单处理状态', 0, 'admin', '2026-11-09 00:00:00', 0, 0
WHERE NOT EXISTS (SELECT 1 FROM sys_dict WHERE dict_code = 'biz_order_status');

INSERT INTO sys_dict_item (id, dict_id, item_text, item_value, sort_order, status, create_by, create_time)
SELECT '179420787753321', d.id, '待处理', 'pending', 1, 1, 'admin', '2026-11-09 00:00:00'
FROM sys_dict d WHERE d.dict_code = 'biz_order_status'
AND NOT EXISTS (SELECT 1 FROM sys_dict_item i WHERE i.dict_id = d.id AND i.item_value = 'pending');

INSERT INTO sys_dict_item (id, dict_id, item_text, item_value, sort_order, status, create_by, create_time)
SELECT '179420787753322', d.id, '处理中', 'processing', 2, 1, 'admin', '2026-11-09 00:00:00'
FROM sys_dict d WHERE d.dict_code = 'biz_order_status'
AND NOT EXISTS (SELECT 1 FROM sys_dict_item i WHERE i.dict_id = d.id AND i.item_value = 'processing');

INSERT INTO sys_dict_item (id, dict_id, item_text, item_value, sort_order, status, create_by, create_time)
SELECT '179420787753323', d.id, '已完成', 'completed', 3, 1, 'admin', '2026-11-09 00:00:00'
FROM sys_dict d WHERE d.dict_code = 'biz_order_status'
AND NOT EXISTS (SELECT 1 FROM sys_dict_item i WHERE i.dict_id = d.id AND i.item_value = 'completed');

INSERT INTO sys_dict_item (id, dict_id, item_text, item_value, sort_order, status, create_by, create_time)
SELECT '179420787753324', d.id, '已取消', 'cancelled', 4, 1, 'admin', '2026-11-09 00:00:00'
FROM sys_dict d WHERE d.dict_code = 'biz_order_status'
AND NOT EXISTS (SELECT 1 FROM sys_dict_item i WHERE i.dict_id = d.id AND i.item_value = 'cancelled');

-- 注意：该页面对应的前台目录为 views/order 文件夹下
-- 如果你想更改到其他目录，请修改sql中component字段对应的值

-- 主菜单
-- ⚠️ is_leaf 必须为 0（第15个值）：有按钮子级时 is_leaf=1 会导致按钮在权限树中不可见
INSERT INTO sys_permission(id, parent_id, name, url, component, component_name, redirect, menu_type, perms, perms_type, sort_no, always_show, icon, is_route, is_leaf, keep_alive, hidden, hide_tab, description, status, del_flag, rule_flag, create_by, create_time, update_by, update_time, internal_or_external)
SELECT '179420787753301', NULL, '订单管理', '/order/bizOrderList', 'order/BizOrderList', NULL, NULL, 0, NULL, '1', 0.00, 0, NULL, 1, 0, 0, 0, 0, NULL, '1', 0, 0, 'admin', '2026-11-09 00:00:00', NULL, NULL, 0 WHERE NOT EXISTS (SELECT 1 FROM sys_permission WHERE id = '179420787753301');

-- 新增
INSERT INTO sys_permission(id, parent_id, name, url, component, is_route, component_name, redirect, menu_type, perms, perms_type, sort_no, always_show, icon, is_leaf, keep_alive, hidden, hide_tab, description, create_by, create_time, update_by, update_time, del_flag, rule_flag, status, internal_or_external)
SELECT '179420787753302', '179420787753301', '添加订单管理', NULL, NULL, 0, NULL, NULL, 2, 'order:biz_order:add', '1', NULL, 0, NULL, 1, 0, 0, 0, NULL, 'admin', '2026-11-09 00:00:00', NULL, NULL, 0, 0, '1', 0 WHERE NOT EXISTS (SELECT 1 FROM sys_permission WHERE id = '179420787753302');

-- 编辑
INSERT INTO sys_permission(id, parent_id, name, url, component, is_route, component_name, redirect, menu_type, perms, perms_type, sort_no, always_show, icon, is_leaf, keep_alive, hidden, hide_tab, description, create_by, create_time, update_by, update_time, del_flag, rule_flag, status, internal_or_external)
SELECT '179420787753303', '179420787753301', '编辑订单管理', NULL, NULL, 0, NULL, NULL, 2, 'order:biz_order:edit', '1', NULL, 0, NULL, 1, 0, 0, 0, NULL, 'admin', '2026-11-09 00:00:00', NULL, NULL, 0, 0, '1', 0 WHERE NOT EXISTS (SELECT 1 FROM sys_permission WHERE id = '179420787753303');

-- 删除
INSERT INTO sys_permission(id, parent_id, name, url, component, is_route, component_name, redirect, menu_type, perms, perms_type, sort_no, always_show, icon, is_leaf, keep_alive, hidden, hide_tab, description, create_by, create_time, update_by, update_time, del_flag, rule_flag, status, internal_or_external)
SELECT '179420787753304', '179420787753301', '删除订单管理', NULL, NULL, 0, NULL, NULL, 2, 'order:biz_order:delete', '1', NULL, 0, NULL, 1, 0, 0, 0, NULL, 'admin', '2026-11-09 00:00:00', NULL, NULL, 0, 0, '1', 0 WHERE NOT EXISTS (SELECT 1 FROM sys_permission WHERE id = '179420787753304');

-- 批量删除
INSERT INTO sys_permission(id, parent_id, name, url, component, is_route, component_name, redirect, menu_type, perms, perms_type, sort_no, always_show, icon, is_leaf, keep_alive, hidden, hide_tab, description, create_by, create_time, update_by, update_time, del_flag, rule_flag, status, internal_or_external)
SELECT '179420787753305', '179420787753301', '批量删除订单管理', NULL, NULL, 0, NULL, NULL, 2, 'order:biz_order:deleteBatch', '1', NULL, 0, NULL, 1, 0, 0, 0, NULL, 'admin', '2026-11-09 00:00:00', NULL, NULL, 0, 0, '1', 0 WHERE NOT EXISTS (SELECT 1 FROM sys_permission WHERE id = '179420787753305');

-- 导出excel
INSERT INTO sys_permission(id, parent_id, name, url, component, is_route, component_name, redirect, menu_type, perms, perms_type, sort_no, always_show, icon, is_leaf, keep_alive, hidden, hide_tab, description, create_by, create_time, update_by, update_time, del_flag, rule_flag, status, internal_or_external)
SELECT '179420787753306', '179420787753301', '导出excel_订单管理', NULL, NULL, 0, NULL, NULL, 2, 'order:biz_order:exportXls', '1', NULL, 0, NULL, 1, 0, 0, 0, NULL, 'admin', '2026-11-09 00:00:00', NULL, NULL, 0, 0, '1', 0 WHERE NOT EXISTS (SELECT 1 FROM sys_permission WHERE id = '179420787753306');

-- 导入excel
INSERT INTO sys_permission(id, parent_id, name, url, component, is_route, component_name, redirect, menu_type, perms, perms_type, sort_no, always_show, icon, is_leaf, keep_alive, hidden, hide_tab, description, create_by, create_time, update_by, update_time, del_flag, rule_flag, status, internal_or_external)
SELECT '179420787753307', '179420787753301', '导入excel_订单管理', NULL, NULL, 0, NULL, NULL, 2, 'order:biz_order:importExcel', '1', NULL, 0, NULL, 1, 0, 0, 0, NULL, 'admin', '2026-11-09 00:00:00', NULL, NULL, 0, 0, '1', 0 WHERE NOT EXISTS (SELECT 1 FROM sys_permission WHERE id = '179420787753307');

-- 角色授权（admin角色）
INSERT INTO sys_role_permission (id, role_id, permission_id, data_rule_ids, operate_date, operate_ip) SELECT '179420787753308', 'f6817f48af4fb3af11b9e8bf182f618b', '179420787753301', NULL, '2026-11-09 00:00:00', '127.0.0.1' WHERE NOT EXISTS (SELECT 1 FROM sys_role_permission WHERE id = '179420787753308');
INSERT INTO sys_role_permission (id, role_id, permission_id, data_rule_ids, operate_date, operate_ip) SELECT '179420787753309', 'f6817f48af4fb3af11b9e8bf182f618b', '179420787753302', NULL, '2026-11-09 00:00:00', '127.0.0.1' WHERE NOT EXISTS (SELECT 1 FROM sys_role_permission WHERE id = '179420787753309');
INSERT INTO sys_role_permission (id, role_id, permission_id, data_rule_ids, operate_date, operate_ip) SELECT '179420787753310', 'f6817f48af4fb3af11b9e8bf182f618b', '179420787753303', NULL, '2026-11-09 00:00:00', '127.0.0.1' WHERE NOT EXISTS (SELECT 1 FROM sys_role_permission WHERE id = '179420787753310');
INSERT INTO sys_role_permission (id, role_id, permission_id, data_rule_ids, operate_date, operate_ip) SELECT '179420787753311', 'f6817f48af4fb3af11b9e8bf182f618b', '179420787753304', NULL, '2026-11-09 00:00:00', '127.0.0.1' WHERE NOT EXISTS (SELECT 1 FROM sys_role_permission WHERE id = '179420787753311');
INSERT INTO sys_role_permission (id, role_id, permission_id, data_rule_ids, operate_date, operate_ip) SELECT '179420787753312', 'f6817f48af4fb3af11b9e8bf182f618b', '179420787753305', NULL, '2026-11-09 00:00:00', '127.0.0.1' WHERE NOT EXISTS (SELECT 1 FROM sys_role_permission WHERE id = '179420787753312');
INSERT INTO sys_role_permission (id, role_id, permission_id, data_rule_ids, operate_date, operate_ip) SELECT '179420787753313', 'f6817f48af4fb3af11b9e8bf182f618b', '179420787753306', NULL, '2026-11-09 00:00:00', '127.0.0.1' WHERE NOT EXISTS (SELECT 1 FROM sys_role_permission WHERE id = '179420787753313');
INSERT INTO sys_role_permission (id, role_id, permission_id, data_rule_ids, operate_date, operate_ip) SELECT '179420787753314', 'f6817f48af4fb3af11b9e8bf182f618b', '179420787753307', NULL, '2026-11-09 00:00:00', '127.0.0.1' WHERE NOT EXISTS (SELECT 1 FROM sys_role_permission WHERE id = '179420787753314');

