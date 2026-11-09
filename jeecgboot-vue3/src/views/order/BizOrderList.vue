<template>
  <div class="p-4">
    <BasicTable @register="registerTable" :row-selection="rowSelection">
      <template #tableTitle>
        <a-button v-auth="'order:biz_order:add'" type="primary" pre-icon="ant-design:plus-outlined" @click="handleAdd">新增</a-button>
        <a-button v-auth="'order:biz_order:exportXls'" pre-icon="ant-design:export-outlined" @click="onExportXls">导出</a-button>
        <JUploadButton v-auth="'order:biz_order:importExcel'" pre-icon="ant-design:import-outlined" @click="onImportXls">导入</JUploadButton>
        <a-button
          v-if="selectedRowKeys.length > 0"
          v-auth="'order:biz_order:deleteBatch'"
          danger
          pre-icon="ant-design:delete-outlined"
          @click="handleBatchDelete"
        >
          批量删除
        </a-button>
      </template>
      <template #action="{ record }">
        <TableAction :actions="getTableActions(record)" />
      </template>
    </BasicTable>
    <BizOrderModal @register="registerModal" @success="handleSuccess" />
  </div>
</template>

<script lang="ts" setup>
  import { BasicTable, TableAction } from '/@/components/Table';
  import { useModal } from '/@/components/Modal';
  import JUploadButton from '/@/components/Button/src/JUploadButton.vue';
  import { useListPage } from '/@/hooks/system/useListPage';
  import { columns, searchFormSchema } from './BizOrder.data';
  import { batchDelete, deleteOne, getExportUrl, getImportUrl, list } from './BizOrder.api';
  import type { BizOrderRecord } from './BizOrder.api';
  import BizOrderModal from './components/BizOrderModal.vue';

  const [registerModal, { openModal }] = useModal();
  const { tableContext, onExportXls, onImportXls, doDeleteRecord, doRequest } = useListPage({
    tableProps: {
      title: '订单管理',
      api: list,
      columns,
      formConfig: { schemas: searchFormSchema, autoSubmitOnEnter: true },
      actionColumn: { width: 180, fixed: 'right' },
    },
    exportConfig: { name: '订单管理', url: getExportUrl },
    importConfig: { url: getImportUrl, success: handleSuccess },
  });
  const [registerTable, { reload }, { rowSelection, selectedRowKeys }] = tableContext;

  function handleAdd() {
    openModal(true, { isUpdate: false, showFooter: true });
  }

  function handleEdit(record: BizOrderRecord) {
    openModal(true, { record, isUpdate: true, showFooter: true });
  }

  function handleDetail(record: BizOrderRecord) {
    openModal(true, { record, isUpdate: true, showFooter: false });
  }

  function handleSuccess() {
    selectedRowKeys.value = [];
    reload();
  }

  function handleBatchDelete() {
    return doRequest(() => batchDelete(selectedRowKeys.value));
  }

  function getTableActions(record: BizOrderRecord) {
    return [
      { label: '编辑', auth: 'order:biz_order:edit', onClick: () => handleEdit(record) },
      { label: '详情', onClick: () => handleDetail(record) },
      {
        label: '删除',
        auth: 'order:biz_order:delete',
        popConfirm: { title: '确认删除订单及全部明细？', confirm: () => doDeleteRecord(() => deleteOne(record.id!)) },
      },
    ];
  }
</script>
