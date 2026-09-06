import { init, id } from '@instantdb/core';
import schema from '../instant.schema';
import { encryptLog } from './utils/crypto';

// Same public application and encryption format as the watch log uploader.
const db = init({ appId: '5565e1e7-5883-45b2-98b0-f3104e643bfc', schema });
let started = false;
window.uploadSupportLog = async (content) => {
  if (started) return;
  started = true;
  try {
    if (typeof content !== 'string' || !content.trim()) throw new Error('日志内容为空');
    window.SupportLog.status('正在加密和压缩日志…');
    const { encryptedBlob, encryptedAesKey } = await encryptLog(content);
    const userId = `phone-${id()}`;
    const path = `${userId}/log_${id()}.enc`;
    window.SupportLog.status('正在上传日志…');
    const { data: file } = await db.storage.uploadFile(path, new File([encryptedBlob], path, { type: 'application/octet-stream' }));
    const code = String(100000 + crypto.getRandomValues(new Uint32Array(1))[0] % 900000);
    window.SupportLog.status('正在保存报错代码…');
    const logId = id();
    await db.transact([
      db.tx.logs[logId].update({ code, encryptedAesKey, fileSize: encryptedBlob.size,
        charCount: content.length, createdAt: new Date().toLocaleString('zh-CN', { timeZone: 'Asia/Shanghai', hour12: false }), userId }),
      db.tx.logs[logId].link({ encryptedFile: file.id }),
    ]);
    window.SupportLog.success(code);
  } catch (_) {
    window.SupportLog.failure('日志上传失败，请检查网络后重试，或直接寻找人工客服。');
  } finally {
    db.shutdown();
  }
};
window.SupportLog.ready();
