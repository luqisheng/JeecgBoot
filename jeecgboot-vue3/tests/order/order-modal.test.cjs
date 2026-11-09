const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');
const ts = require('typescript');
const vue = require('vue');
const { parse, compileScript } = require('@vue/compiler-sfc');

const projectRoot = path.resolve(__dirname, '../..');
const viewRoot = path.join(projectRoot, 'src/views/order');

// 执行真实的 SFC 脚本，仅替换网络和页面框架边界，避免测试复制弹窗加载逻辑。
function loadModule(source, filename, dependencies = {}) {
  const compiled = ts.transpileModule(source, {
    compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.CommonJS, esModuleInterop: true },
  }).outputText;
  const module = { exports: {} };
  vm.runInNewContext(
    compiled,
    {
      module,
      exports: module.exports,
      require: (name) => (Object.hasOwn(dependencies, name) ? dependencies[name] : require(name)),
    },
    { filename }
  );
  return module.exports;
}

function deferred() {
  let resolve;
  let reject;
  const promise = new Promise((resolvePromise, rejectPromise) => {
    resolve = resolvePromise;
    reject = rejectPromise;
  });
  return { promise, resolve, reject };
}

function createModal(loadOrder, loadItems) {
  let open;
  let closed = 0;
  const fields = {};
  const http = {
    defHttp: {
      get: ({ url, params }) => (url.endsWith('/queryById') ? loadOrder(params.id) : loadItems(params.id)),
    },
  };
  const api = loadModule(fs.readFileSync(path.join(viewRoot, 'BizOrder.api.ts'), 'utf8'), 'BizOrder.api.ts', {
    '/@/utils/http/axios': http,
  });
  const vxeTypes = loadModule(
    fs.readFileSync(path.join(projectRoot, 'src/components/jeecg/JVxeTable/src/types/JVxeTypes.ts'), 'utf8'),
    'JVxeTypes.ts'
  );
  const data = loadModule(fs.readFileSync(path.join(viewRoot, 'BizOrder.data.ts'), 'utf8'), 'BizOrder.data.ts', {
    '/@/components/jeecg/JVxeTable/types': vxeTypes,
  });
  const filename = path.join(viewRoot, 'components/BizOrderModal.vue');
  const { descriptor } = parse(fs.readFileSync(filename, 'utf8'), { filename });
  const script = compileScript(descriptor, { id: 'order-modal-test' });
  const component = loadModule(script.content, filename, {
    vue,
    '/@/components/Form': {
      BasicForm: {},
      useForm: () => [
        () => {},
        {
          setProps: () => {},
          resetFields: async () => {
            Object.keys(fields).forEach((key) => delete fields[key]);
          },
          setFieldsValue: async (value) => {
            Object.assign(fields, value);
          },
        },
      ],
    },
    '/@/components/Modal': {
      BasicModal: {},
      useModalInner: (callback) => {
        open = callback;
        return [
          () => {},
          {
            setModalProps: () => {},
            closeModal: () => {
              closed++;
            },
          },
        ];
      },
    },
    '/@/components/jeecg/JVxeTable': { JVxeTable: {} },
    '/@/hooks/system/useJvxeMethods': { useJvxeMethod: () => [() => {}, () => {}, undefined, vue.ref()] },
    '/@/hooks/web/useMessage': { useMessage: () => ({ createMessage: { warning: () => {} } }) },
    '/@/utils/http/axios': http,
    '../BizOrder.data': data,
    '../BizOrder.api': api,
  }).default;
  const state = component.setup({}, { expose: () => {}, emit: () => {} });
  return { open: (id, isUpdate = true) => open({ record: { id }, isUpdate, showFooter: true }), fields, state, closed: () => closed };
}

const nextTurn = () => new Promise((resolve) => setImmediate(resolve));

test('旧订单的慢明细响应不能覆盖后来打开的订单', async () => {
  const oldItems = deferred();
  const modal = createModal(
    async (id) => ({ id, customerName: id, orderStatus: 'pending', totalAmount: '1.00' }),
    (id) => (id === 'A' ? oldItems.promise : Promise.resolve([{ productName: 'B商品', quantity: 1, unitPrice: '2.00' }]))
  );
  const first = modal.open('A');
  await nextTurn();
  await modal.open('B');
  oldItems.resolve([{ productName: 'A商品', quantity: 1, unitPrice: '1.00' }]);
  await first;
  assert.equal(modal.fields.id, 'B');
  assert.equal(modal.state.itemTable.dataSource[0].productName, 'B商品');
  assert.equal(modal.state.itemTable.loading, false);
});

test('旧主表请求失败不能关闭后来打开的订单', async () => {
  const oldOrder = deferred();
  const modal = createModal(
    (id) => (id === 'A' ? oldOrder.promise : Promise.resolve({ id, customerName: id, orderStatus: 'pending' })),
    async () => [{ productName: 'B商品', quantity: 1, unitPrice: '1.00' }]
  );
  const first = modal.open('A');
  await nextTurn();
  await modal.open('B');
  oldOrder.reject(new Error('旧请求失败'));
  await first;
  assert.equal(modal.fields.id, 'B');
  assert.equal(modal.closed(), 0);
});

test('在旧请求未完成时打开新增订单能解除加载状态', async () => {
  const oldItems = deferred();
  const modal = createModal(
    async (id) => ({ id, customerName: id, orderStatus: 'pending' }),
    () => oldItems.promise
  );
  const first = modal.open('A');
  await nextTurn();
  await modal.open(undefined, false);
  oldItems.resolve([{ productName: '旧商品', quantity: 1, unitPrice: '1.00' }]);
  await first;
  assert.equal(modal.state.itemTable.loading, false);
  assert.equal(modal.state.itemTable.dataSource.length, 0);
  assert.equal(modal.fields.id, undefined);
});
