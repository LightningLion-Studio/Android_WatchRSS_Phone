# 客服诊断日志上传

复用手表 `Loger_key/LogUploader/LogUploader` 的 InstantDB 公开应用 ID、schema、RSA 公钥与 gzip → AES-GCM → RSA-OAEP 加密格式。这里只包含公钥，不包含解密私钥或管理令牌。Native 仅在用户点击日志同意按钮后创建上传器并传入脱敏诊断日志；取件码在文件上传与记录事务成功后才回传。

构建：在本目录运行 `npm ci --ignore-scripts && npm run build`。产物为 `app/src/main/assets/support_log_upload`；客户端使用本地 HTTPS 资源映射加载，不依赖在线网页。修改手表服务 schema/公钥时需同步核对本目录，并通过真实合成日志测试。

`SupportHandoffDeviceTest` 的真实服务测试只上传不含用户数据的合成文本。其余用例通过 18089 端口独立 HTTP fixture 与内存账号验证拒绝零采集、失败重试、复制、注销取消和悬浮窗，不替换机主身份。
