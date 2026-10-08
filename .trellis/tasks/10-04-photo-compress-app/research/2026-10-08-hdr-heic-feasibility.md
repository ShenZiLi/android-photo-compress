# HDR HEIC 压缩可行性（2026-10-08）

用户希望支持 HDR 照片压缩，上下文为 HEIC 支持范围。已询问优先格式及本机 HDR 样例路径，尚未得到真实 HDR HEIC 样例。没有修改产品代码、版本或手机安装包；不通过删除保护条件来允许 HDR 降为 SDR。

## 已确认的边界

- JPEG/MPF 现有路径保留增益图/辅助图和参数，详见既有 MPF 规范与验收；该结论不推广到任意 HDR 格式。
- HEIC 当前 HeicContainer 拒绝主图 PQ/HLG、高位深及 auxl，HeicCompressor 固定 ARGB_8888 输入且拒绝解码 gainmap。
- 已安装 AndroidX HeifWriter 1.1.0 的 Builder API 没有高位深选项。官方最新 HeifEncoder 源码仍向 EncoderBase 和 setup 传 useBitDepth10=false。库发布记录的 10-bit 支持不能等同于 HeifWriter 的 HEIC 10-bit 输入；AvifWriter 有单独高位深 API。升级版本或解除输入检查不能证明 HEIC HDR 保真。
- IMG_2253.HEIC 的主图为 8 位、Display P3，容器无 auxC/auxl/tmap；不能作为 HDR HEIC 验收样例。本轮只读取手机末尾 80 份 HEIC 的头部，也未发现 auxC/auxl/tmap；非全面清点，不证明手机没有 HDR。

## 可行实现路线

1. 8 位 SDR 基础图 + HDR gainmap HEIC：严格识别已知增益图关联，只重编码基础图，所有 gainmap 像素载荷、元数据、引用与色彩原样保留；输出读回检查基础图和 HDR 关联完整。不得笼统放开 auxl，以免误处理透明、深度或其他派生图。Apple HDR 和 ISO tmap 需要分别解析，若 primary 为 tmap 要选 base item 而不是把 tmap 当编码像素。
2. 10 位 PQ/HLG HEIC：需要专门的 Main10 图像编码输入链，保留精度、色域、传递函数与编码信令，不能复用现有8位 bitmap HeifWriter 路径。手机 Main10 声明可作为能力前提，不等于已证明高位深图像端到端可用。

下一步需要真实 HDR HEIC 样例（用户本机路径），按样例确定类型与安全支持范围，并实施/验收压缩、HDR显示、容器完整性及还原。当前没有完成新增 HDR HEIC 支持，也没有执行 HDR 专项编码/还原或测试套件。

## 官方依据

- [AndroidX HeifWriter 发行说明](https://developer.android.com/jetpack/androidx/releases/heifwriter)（2026-10-07 最新 rc01）
- [AndroidX HeifEncoder 源码](https://github.com/androidx/androidx/blob/androidx-main/heifwriter/heifwriter/src/main/java/androidx/heifwriter/HeifEncoder.java)：useBitDepth10=false。
- [AndroidX AvifWriter.Builder](https://developer.android.com/reference/androidx/heifwriter/AvifWriter.Builder)：setHighBitDepthEnabled。
- [Apple HDR gainmap](https://developer.apple.com/documentation/appkit/applying-apple-hdr-effect-to-your-photos)：已知辅助图类型 urn:com:apple:photo:2020:aux:hdrgainmap。

GitHub 内容通过 agent-reach 的 GitHub CLI 读取；临时内容保存在 /tmp，诊断在忽略目录 .tmp-device/heic-hdr。外部 CTS 样例目录访问超时，未将其作为验证结果。其他正在进行的性能任务修改保留，不纳入本次提交。

## 当前手机只读能力探针

本轮在已连接 RMX5010 读取 MediaCodecInfo：c2.qti.hevc.encoder、其 cq/hdr 变体及对应 OMX 编码器均为 hardware=true，声明 Main10（profiles 1,4,2,4096,8192,8）；软件 c2.android.hevc.encoder 仅含 profiles 1,4。本探针只读编码器声明，未把声明能力当作 HDR HEIC 编码验收。未改动用户媒体或应用数据。
