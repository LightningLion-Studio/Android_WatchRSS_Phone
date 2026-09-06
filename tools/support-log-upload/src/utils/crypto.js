// 导入 RSA 公钥
import { PUBLIC_KEY_PEM } from '../config/publicKey.js';

/**
 * 压缩文本数据
 * @param {string} text - 要压缩的文本
 * @returns {Promise<Uint8Array>} 压缩后的数据
 */
export async function compressText(text) {
  const encoder = new TextEncoder();
  const data = encoder.encode(text);

  const stream = new ReadableStream({
    start(controller) {
      controller.enqueue(data);
      controller.close();
    }
  });

  const compressedStream = stream.pipeThrough(new CompressionStream('gzip'));
  const reader = compressedStream.getReader();
  const chunks = [];

  while (true) {
    const { done, value } = await reader.read();
    if (done) break;
    chunks.push(value);
  }

  // 合并所有 chunks
  const totalLength = chunks.reduce((acc, chunk) => acc + chunk.length, 0);
  const result = new Uint8Array(totalLength);
  let offset = 0;
  for (const chunk of chunks) {
    result.set(chunk, offset);
    offset += chunk.length;
  }

  return result;
}

/**
 * 生成随机 AES 密钥
 * @returns {Promise<CryptoKey>} AES-GCM 密钥
 */
export async function generateAESKey() {
  return await crypto.subtle.generateKey(
    {
      name: 'AES-GCM',
      length: 256
    },
    true, // 可导出
    ['encrypt', 'decrypt']
  );
}

/**
 * 使用 AES 加密数据
 * @param {CryptoKey} aesKey - AES 密钥
 * @param {Uint8Array} data - 要加密的数据
 * @returns {Promise<{encrypted: Uint8Array, iv: Uint8Array}>} 加密后的数据和 IV
 */
export async function encryptWithAES(aesKey, data) {
  const iv = crypto.getRandomValues(new Uint8Array(12)); // GCM 推荐 12 字节 IV

  const encrypted = await crypto.subtle.encrypt(
    {
      name: 'AES-GCM',
      iv: iv
    },
    aesKey,
    data
  );

  return {
    encrypted: new Uint8Array(encrypted),
    iv: iv
  };
}

/**
 * 导入 RSA 公钥
 * @returns {Promise<CryptoKey>} RSA 公钥
 */
export async function importRSAPublicKey() {
  // 移除 PEM 头尾和换行符
  const pemContents = PUBLIC_KEY_PEM
    .replace('-----BEGIN PUBLIC KEY-----', '')
    .replace('-----END PUBLIC KEY-----', '')
    .replace(/\s/g, '');

  // Base64 解码
  const binaryDer = atob(pemContents);
  const bytes = new Uint8Array(binaryDer.length);
  for (let i = 0; i < binaryDer.length; i++) {
    bytes[i] = binaryDer.charCodeAt(i);
  }

  // 导入公钥
  return await crypto.subtle.importKey(
    'spki',
    bytes.buffer,
    {
      name: 'RSA-OAEP',
      hash: 'SHA-256'
    },
    true,
    ['encrypt']
  );
}

/**
 * 使用 RSA 公钥加密 AES 密钥
 * @param {CryptoKey} rsaPublicKey - RSA 公钥
 * @param {CryptoKey} aesKey - AES 密钥
 * @returns {Promise<Uint8Array>} 加密后的 AES 密钥
 */
export async function encryptAESKeyWithRSA(rsaPublicKey, aesKey) {
  // 导出 AES 密钥为原始格式
  const rawAesKey = await crypto.subtle.exportKey('raw', aesKey);

  // 使用 RSA 公钥加密
  const encrypted = await crypto.subtle.encrypt(
    {
      name: 'RSA-OAEP'
    },
    rsaPublicKey,
    rawAesKey
  );

  return new Uint8Array(encrypted);
}

/**
 * 将 Uint8Array 转换为 Base64 字符串
 * @param {Uint8Array} bytes - 字节数组
 * @returns {string} Base64 字符串
 */
export function bytesToBase64(bytes) {
  let binary = '';
  for (let i = 0; i < bytes.length; i++) {
    binary += String.fromCharCode(bytes[i]);
  }
  return btoa(binary);
}

/**
 * 将加密数据和 IV 组合成一个 Blob
 * @param {Uint8Array} encrypted - 加密后的数据
 * @param {Uint8Array} iv - 初始化向量
 * @returns {Blob} 包含 IV 和加密数据的 Blob
 */
export function createEncryptedBlob(encrypted, iv) {
  // 格式: [IV 长度(1字节)][IV][加密数据]
  const combined = new Uint8Array(1 + iv.length + encrypted.length);
  combined[0] = iv.length;
  combined.set(iv, 1);
  combined.set(encrypted, 1 + iv.length);

  return new Blob([combined], { type: 'application/octet-stream' });
}

/**
 * 完整的加密流程
 * @param {string} logText - 日志文本
 * @returns {Promise<{encryptedBlob: Blob, encryptedAesKey: string}>}
 */
export async function encryptLog(logText) {
  // 1. 压缩文本
  const compressed = await compressText(logText);

  // 2. 生成 AES 密钥
  const aesKey = await generateAESKey();

  // 3. 使用 AES 加密压缩后的数据
  const { encrypted, iv } = await encryptWithAES(aesKey, compressed);

  // 4. 导入 RSA 公钥
  const rsaPublicKey = await importRSAPublicKey();

  // 5. 使用 RSA 加密 AES 密钥
  const encryptedAesKeyBytes = await encryptAESKeyWithRSA(rsaPublicKey, aesKey);

  // 6. 创建包含 IV 和加密数据的 Blob
  const encryptedBlob = createEncryptedBlob(encrypted, iv);

  // 7. 将加密的 AES 密钥转换为 Base64
  const encryptedAesKey = bytesToBase64(encryptedAesKeyBytes);

  return {
    encryptedBlob,
    encryptedAesKey
  };
}
