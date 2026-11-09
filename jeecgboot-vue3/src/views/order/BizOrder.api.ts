import { defHttp } from '/@/utils/http/axios';

export interface BizOrderItemRecord {
  id?: string;
  orderId?: string;
  productName: string;
  quantity: number | string;
  unitPrice: string;
  subtotalAmount?: string;
}

export interface BizOrderRecord {
  id?: string;
  orderNo?: string;
  customerName: string;
  totalAmount?: string;
  orderStatus: string;
  createTime?: string;
}

export interface BizOrderPayload extends BizOrderRecord {
  bizOrderItemList: BizOrderItemRecord[];
}

enum Api {
  List = '/order/bizOrder/list',
  Add = '/order/bizOrder/add',
  Edit = '/order/bizOrder/edit',
  Delete = '/order/bizOrder/delete',
  DeleteBatch = '/order/bizOrder/deleteBatch',
  QueryById = '/order/bizOrder/queryById',
  Items = '/order/bizOrder/queryBizOrderItemByMainId',
  ImportExcel = '/order/bizOrder/importExcel',
  ExportXls = '/order/bizOrder/exportXls',
}

export const getImportUrl = Api.ImportExcel;
export const getExportUrl = Api.ExportXls;
// useJvxeMethod 的子表查询接收 URL 字符串。
export const queryBizOrderItemByMainId = Api.Items;

export const list = (params: Record<string, unknown>) => defHttp.get({ url: Api.List, params });
export const queryById = (id: string) => defHttp.get<BizOrderRecord & { id: string }>({ url: Api.QueryById, params: { id } });

/** 删除订单和明细，确认操作由列表组件负责。 */
export const deleteOne = (id: string) => defHttp.delete({ url: Api.Delete, params: { id } }, { joinParamsToUrl: true });

export const batchDelete = (ids: string[]) => defHttp.delete({ url: Api.DeleteBatch, params: { ids: ids.join(',') } }, { joinParamsToUrl: true });

/** 提交主表和完整明细列表；金额以后台计算结果为准。 */
export const saveOrUpdate = (params: BizOrderPayload, isUpdate: boolean) => defHttp.post({ url: isUpdate ? Api.Edit : Api.Add, params });
