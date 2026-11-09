# 订单管理

后端模块：`jeecg-boot/jeecg-module-system/jeecg-system-biz`，包名 `org.jeecg.modules.order`。
前端页面：`jeecgboot-vue3/src/views/order/BizOrderList.vue`。
菜单路由：`/order/bizOrderList`，组件：`order/BizOrderList`。

生成文件清单：

| 目录（相对上述模块或前端根目录） | 文件 |
| --- | --- |
| `src/main/java/org/jeecg/modules/order/entity/` | `BizOrder.java`、`BizOrderItem.java` |
| `src/main/java/org/jeecg/modules/order/controller/` | `BizOrderController.java` |
| `src/main/java/org/jeecg/modules/order/service/` | `IBizOrderService.java`、`IBizOrderItemService.java`、`OrderAmountCalculator.java` |
| `src/main/java/org/jeecg/modules/order/service/impl/` | `BizOrderServiceImpl.java`、`BizOrderItemServiceImpl.java` |
| `src/main/java/org/jeecg/modules/order/mapper/` | `BizOrderMapper.java`、`BizOrderItemMapper.java` |
| `src/main/java/org/jeecg/modules/order/mapper/xml/` | `BizOrderMapper.xml`、`BizOrderItemMapper.xml` |
| `src/test/java/org/jeecg/modules/order/service/` | `OrderAmountCalculatorTest.java`、`BizOrderServiceIntegrationTest.java` |
| `src/views/order/` | `BizOrder.api.ts`、`BizOrder.data.ts`、`BizOrderList.vue` |
| `src/views/order/components/` | `BizOrderModal.vue` |
| `tests/order/` | `order-modal.test.cjs` |

另包含下文列出的 Flyway 迁移脚本和本说明，共 22 个新增文件。

## 数据和业务规则

- 订单：`biz_order`；明细：`biz_order_item`，通过 `order_id` 关联。
- 订单编号由后端生成，格式为 `ORD` 加雪花编号，数据库唯一，编辑时保留原编号。
- 客户和商品保存名称，分别最多 100 和 200 个字符，不能只包含空格。
- 订单至少一条明细；数量为正整数，最大 2147483647。
- 单价非负且最多两位小数；单价、小计和订单金额均不能超过 `9999999999999999.99`。
- 后端计算小计和总金额，忽略客户端提交的金额。JSON 金额使用字符串，前端使用十进制计算，单价数字输入启用字符串模式。
- 订单字典 `biz_order_status`：`pending` 待处理、`processing` 处理中、`completed` 已完成、`cancelled` 已取消。新增缺省为待处理，可在表单中选择状态。
- 新增、编辑、删除和批量删除在同一事务中处理主表与明细。编辑整体替换明细，明细主键重新生成。
- 修改和删除先锁定主表行，保证同一订单的写入串行处理。未增加版本字段，先后提交的有效编辑按提交顺序生效。
- 订单和明细的审计信息由框架填充，不能通过提交数据覆盖创建人和创建时间。
- 数量 JSON 输入使用精确整数转换，拒绝小数和整数溢出；金额使用 Jackson 2/3 共用的字符串格式注解。
- 弹窗按打开次数检查请求归属，旧订单的慢响应不会覆盖当前订单或关闭当前弹窗。

## 接口和权限

接口基路径为 `/order/bizOrder`：

| 接口 | 方法 | 说明 |
| --- | --- | --- |
| `/list` | GET | 分页查询 |
| `/queryById` | GET | 订单详情 |
| `/queryBizOrderItemByMainId` | GET | 按订单主键查询完整明细 |
| `/add` | POST | 新增订单与明细 |
| `/edit` | POST / PUT | 编辑订单与完整明细 |
| `/delete` | DELETE | 删除订单与明细 |
| `/deleteBatch` | DELETE | 批量删除，`ids` 为逗号分隔的主键 |
| `/exportXls` | GET | 导出筛选结果或选中订单 |
| `/importExcel` | POST | 上传一个 Excel 文件 |

新增和编辑的请求体：

```json
{
  "customerName": "示例客户",
  "orderStatus": "pending",
  "bizOrderItemList": [
    {
      "productName": "示例商品",
      "quantity": 3,
      "unitPrice": "19.90"
    }
  ]
}
```

编辑请求还需包含主表 `id`。新增不接受客户端主键、编号和审计信息。
写入及 Excel 操作使用 `order:biz_order:{操作}` 权限编码，与前端按钮和迁移中的授权一致。

Excel 主表列排在明细列之前并合并同一订单的单元格，保证导出后的多条明细重新导入时仍属于一张订单。
导入创建新订单和新编号，并重新计算金额；一份文件的任意订单失败时，整份文件回滚。

## 数据库迁移

迁移脚本：
`jeecg-boot/jeecg-module-system/jeecg-system-start/src/main/resources/flyway/sql/mysql/V20261109_1__create_order_management.sql`。

脚本包含两张表、唯一编号索引、明细外键、订单字典、菜单、操作权限及管理员授权。
管理员角色 ID 已与当前开发库核对。脚本可重复执行，以兼容本地先初始化、随后由 Flyway 记录迁移的流程。
开发配置的 Flyway 路径为 `classpath:flyway/sql/mysql`；应用启动时是否执行仍取决于实际运行参数。
加载新后端代码后，重新登录或刷新权限菜单可进入订单管理。

本轮已在本地 `docker-mysql`（端口 3307）的 `jeecg-boot` 数据库执行迁移。
两张业务表为空；4 个中文状态字典项、主菜单、6 个操作权限和管理员的 7 条授权均已核对。
独立测试库已清理，业务库没有写入测试订单。

## 验证

后端金额测试：

```powershell
Set-Location D:\carsmaga\JeecgBoot\jeecg-boot
mvn.cmd -o -pl jeecg-module-system/jeecg-system-biz "-DskipTests=false" "-Dtest=OrderAmountCalculatorTest" "-Dmaven.compiler.proc=full" test
```

Java 25 需要显式启用注解处理才能生成 Lombok 方法；上面的参数不会修改项目编译配置。

MySQL 集成测试需要独立本地数据库，名称必须以 `codex_order_` 开头。
在测试库中准备迁移里的两张业务表；如果执行完整迁移，还需要独立的 `sys_dict`、`sys_dict_item`、
`sys_permission` 和 `sys_role_permission` 表，可通过 `CREATE TABLE ... LIKE` 复制开发库结构。

```powershell
$env:JEECG_ORDER_TEST_URL = 'jdbc:mysql://127.0.0.1:3307/codex_order_example?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai'
$env:JEECG_ORDER_TEST_USER = 'root'
# 在当前终端设置 JEECG_ORDER_TEST_PASSWORD，不将密码写入代码或文档。
mvn.cmd -o -pl jeecg-module-system/jeecg-system-biz "-DskipTests=false" "-Dtest=OrderAmountCalculatorTest,BizOrderServiceIntegrationTest" "-Dmaven.compiler.proc=full" test
```

集成测试会清空测试库的订单表，并临时创建失败触发器，验证新增、编辑和导入回滚。
未配置测试 URL 时，这组集成测试会跳过。

前端检查在 `jeecgboot-vue3` 目录运行：

```powershell
node node_modules/eslint/bin/eslint.js src/views/order --ext .ts,.vue --max-warnings 0
node node_modules/prettier/bin/prettier.cjs --check "src/views/order/**/*.{ts,vue}"
node --test tests/order/order-modal.test.cjs
$env:NODE_ENV = 'production'
node --max-old-space-size=8192 node_modules/vite/bin/vite.js build
```

现有 `vue-tsc 1.8` 与本机安装的 TypeScript 版本不兼容。本轮用临时固定版本的
`vue-tsc 2.2.12` 和 `TypeScript 5.8.3` 检查订单页面，未修改依赖或锁文件；
其依赖的既有页面仍有类型错误，需要在规范治理任务中逐步处理。

本轮验证：后端 24 项测试通过，前端异步回归 3 项通过；前端生产构建通过，
订单页面 ESLint 零警告，临时类型检查未报告订单文件错误。既有依赖代码的类型检查仍有 852 条错误。
