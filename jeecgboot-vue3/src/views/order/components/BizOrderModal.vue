<template>
  <BasicModal v-bind="$attrs" @register="registerModal" :title="title" :width="960" @ok="handleSubmit">
    <BasicForm ref="formRef" name="BizOrderForm" @register="registerForm" />
    <a-tabs v-model:active-key="activeKey" @change="handleChangeTabs">
      <a-tab-pane key="bizOrderItem" tab="订单明细" force-render>
        <JVxeTable
          ref="bizOrderItem"
          keep-source
          resizable
          :loading="itemTable.loading"
          :columns="itemTable.columns"
          :data-source="itemTable.dataSource"
          :height="340"
          :disabled="formDisabled || itemTable.loading"
          row-number
          :row-selection="!formDisabled"
          :toolbar="!formDisabled"
          @value-change="refreshTotal"
          @added="refreshTotal"
          @removed="refreshTotal"
        >
          <template #unitPrice="{ value, triggerChange }">
            <a-input-number
              :value="value"
              string-mode
              :precision="2"
              min="0"
              max="9999999999999999.99"
              :disabled="formDisabled || itemTable.loading"
              @change="triggerChange"
            />
          </template>
          <template #subtotalAmount="{ row }">
            {{ calculateSubtotal(row) }}
          </template>
        </JVxeTable>
      </a-tab-pane>
    </a-tabs>
  </BasicModal>
</template>

<script lang="ts" setup>
  import Big from 'big.js';
  import { computed, nextTick, reactive, ref } from 'vue';
  import type { Ref } from 'vue';
  import { BasicForm, useForm } from '/@/components/Form';
  import { BasicModal, useModalInner } from '/@/components/Modal';
  import { JVxeTable } from '/@/components/jeecg/JVxeTable';
  import type { JVxeTableInstance } from '/@/components/jeecg/JVxeTable/types';
  import { useJvxeMethod } from '/@/hooks/system/useJvxeMethods';
  import { useMessage } from '/@/hooks/web/useMessage';
  import { defHttp } from '/@/utils/http/axios';
  import { bizOrderItemJVxeColumns, calculateSubtotal, formSchema } from '../BizOrder.data';
  import { queryBizOrderItemByMainId, queryById, saveOrUpdate } from '../BizOrder.api';
  import type { BizOrderItemRecord, BizOrderPayload, BizOrderRecord } from '../BizOrder.api';

  const emit = defineEmits(['register', 'success']);
  const { createMessage } = useMessage();
  const isUpdate = ref(false);
  const formDisabled = ref(false);
  const refKeys = ref(['bizOrderItem']);
  const activeKey = ref('bizOrderItem');
  let loadVersion = 0;
  const bizOrderItem = ref<JVxeTableInstance>();
  const tableRefs = { bizOrderItem };
  const itemTable = reactive({ loading: false, dataSource: [] as BizOrderItemRecord[], columns: bizOrderItemJVxeColumns });
  const [registerForm, { setProps, resetFields, setFieldsValue }] = useForm({
    schemas: formSchema,
    showActionButtonGroup: false,
    baseColProps: { span: 24 },
  });
  const [handleChangeTabs, handleSubmit, , formRef] = useJvxeMethod(requestAddOrEdit, classifyIntoFormData, tableRefs, activeKey, refKeys) as [
    (key: string) => void,
    () => Promise<unknown>,
    unknown,
    Ref<unknown>,
  ];
  const [registerModal, { setModalProps, closeModal }] = useModalInner(async (data) => {
    const version = ++loadVersion;
    await resetFields();
    if (version !== loadVersion) return;
    activeKey.value = 'bizOrderItem';
    itemTable.dataSource = [];
    itemTable.loading = false;
    isUpdate.value = Boolean(data?.isUpdate);
    formDisabled.value = !data?.showFooter;
    setProps({ disabled: formDisabled.value });
    setModalProps({ confirmLoading: false, showOkBtn: !formDisabled.value });
    if (isUpdate.value) {
      itemTable.loading = true;
      try {
        // 重新读取主表，避免列表中的旧金额和状态覆盖当前数据。
        const order = await queryById(data.record.id);
        if (version !== loadVersion) return;
        await setFieldsValue(order);
        const items = await defHttp.get<BizOrderItemRecord[]>({ url: queryBizOrderItemByMainId, params: { id: order.id } });
        // 只接收当前打开订单的响应，避免较慢的旧请求覆盖另一张订单的明细。
        if (version !== loadVersion) return;
        itemTable.dataSource = items;
      } catch (error) {
        if (version !== loadVersion) return;
        closeModal();
        throw error;
      } finally {
        if (version === loadVersion) itemTable.loading = false;
      }
    }
  });

  const title = computed(() => (formDisabled.value ? '订单详情' : isUpdate.value ? '编辑订单' : '新增订单'));

  async function refreshTotal() {
    await nextTick();
    const rows = (bizOrderItem.value?.getTableData() ?? itemTable.dataSource) as BizOrderItemRecord[];
    const total = rows.reduce((amount, item) => amount.plus(calculateSubtotal(item)), new Big(0));
    await setFieldsValue({ totalAmount: total.toFixed(2) });
  }

  function classifyIntoFormData(allValues: { formValue: BizOrderRecord; tablesValue: { tableData: BizOrderItemRecord[] }[] }): BizOrderPayload {
    return {
      ...allValues.formValue,
      bizOrderItemList: allValues.tablesValue[0].tableData.map((item) => ({
        productName: item.productName,
        quantity: Number(item.quantity),
        unitPrice: String(item.unitPrice),
      })),
    };
  }

  async function requestAddOrEdit(values: BizOrderPayload) {
    if (itemTable.loading) {
      createMessage.warning('订单明细正在加载，请稍后保存');
      return;
    }
    if (values.bizOrderItemList.length === 0) {
      createMessage.warning('请至少添加一条订单明细');
      return;
    }
    try {
      setModalProps({ confirmLoading: true });
      await saveOrUpdate(values, isUpdate.value);
      closeModal();
      emit('success');
    } finally {
      setModalProps({ confirmLoading: false });
    }
  }
</script>

<style lang="less" scoped>
  :deep(.ant-input-number) {
    width: 100%;
  }
</style>
