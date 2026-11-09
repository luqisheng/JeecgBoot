import Big from 'big.js';
import type { BasicColumn, FormSchema } from '/@/components/Table';
import { JVxeTypes } from '/@/components/jeecg/JVxeTable/types';
import type { JVxeColumn } from '/@/components/jeecg/JVxeTable/types';
import type { BizOrderItemRecord } from './BizOrder.api';

export const columns: BasicColumn[] = [
  { title: '订单编号', dataIndex: 'orderNo', width: 240 },
  { title: '客户', dataIndex: 'customerName', width: 180 },
  { title: '金额', dataIndex: 'totalAmount', width: 160, align: 'right' },
  { title: '状态', dataIndex: 'orderStatus_dictText', width: 120 },
  { title: '创建时间', dataIndex: 'createTime', width: 180 },
];

export const searchFormSchema: FormSchema[] = [
  { label: '订单编号', field: 'orderNo', component: 'JInput' },
  { label: '客户', field: 'customerName', component: 'JInput' },
  { label: '状态', field: 'orderStatus', component: 'JDictSelectTag', componentProps: { dictCode: 'biz_order_status' } },
];

export const formSchema: FormSchema[] = [
  { label: '', field: 'id', component: 'Input', show: false },
  { label: '订单编号', field: 'orderNo', component: 'Input', componentProps: { readonly: true, placeholder: '保存时自动生成' } },
  {
    label: '客户',
    field: 'customerName',
    component: 'Input',
    required: true,
    rules: [{ required: true, whitespace: true, message: '请输入客户名称' }],
    componentProps: { maxLength: 100 },
  },
  {
    label: '金额',
    field: 'totalAmount',
    component: 'InputNumber',
    defaultValue: '0.00',
    componentProps: { stringMode: true, precision: 2, readonly: true, controls: false },
  },
  {
    label: '状态',
    field: 'orderStatus',
    component: 'JDictSelectTag',
    defaultValue: 'pending',
    required: true,
    componentProps: { dictCode: 'biz_order_status', type: 'select' },
  },
];

export const bizOrderItemJVxeColumns: JVxeColumn[] = [
  { title: '', key: 'id', type: JVxeTypes.hidden },
  {
    title: '商品',
    key: 'productName',
    type: JVxeTypes.input,
    width: '240px',
    defaultValue: '',
    props: { maxLength: 200 },
    validateRules: [
      { required: true, message: '请输入商品名称' },
      { pattern: /\S/, message: '商品名称不能只包含空格' },
    ],
  },
  {
    title: '数量',
    key: 'quantity',
    type: JVxeTypes.inputNumber,
    width: '130px',
    defaultValue: '',
    validateRules: [
      { required: true, message: '请输入数量' },
      { pattern: /^[1-9]\d*$/, message: '数量必须为正整数' },
      {
        handler({ cellValue }, callback) {
          callback(Number(cellValue) <= 2147483647, '数量超过整数上限');
        },
      },
    ],
  },
  {
    title: '单价',
    key: 'unitPrice',
    type: JVxeTypes.slot,
    slotName: 'unitPrice',
    width: '200px',
    defaultValue: '',
    validateRules: [
      { required: true, message: '请输入单价' },
      { pattern: /^\d+(\.\d{1,2})?$/, message: '单价必须为非负金额，最多两位小数' },
    ],
  },
  { title: '小计', key: 'subtotalAmount', type: JVxeTypes.slot, slotName: 'subtotalAmount', width: '180px', defaultValue: '0.00' },
];

/** 使用十进制字符串计算显示金额；保存时后端再次校验和计算。 */
export function calculateSubtotal(item: BizOrderItemRecord): string {
  if (!/^[1-9]\d*$/.test(String(item.quantity)) || !/^\d+(\.\d{1,2})?$/.test(String(item.unitPrice))) {
    return '0.00';
  }
  return new Big(String(item.unitPrice)).times(String(item.quantity)).toFixed(2);
}
