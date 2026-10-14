/**
 * useChunkedUpload —— 大文件分片上传
 *
 * ═══════════════════════════════════════════════════════════════
 * 背景（重要）
 * ═══════════════════════════════════════════════════════════════
 * 本文件是补写的。原项目里 UploadDialog.vue 已经 import 了它，
 * 但文件本身从未提交到仓库 → 导致「文档管理」页面加载失败（白屏）。
 *
 * 它的职责很简单：把 api/document.ts 里**已经实现好**的三个接口
 * 串成一条完整流程：
 *
 *     计算文件指纹 → init（可能秒传）→ 循环上传分片 → complete（合并）
 *
 * 后端接口（均已存在）：
 *   POST /api/documents/upload/init          初始化（含秒传判断 + 断点续传信息）
 *   POST /api/documents/upload/chunks        上传单个分片
 *   POST /api/documents/upload/{id}/complete 合并分片并建文档
 *
 * ═══════════════════════════════════════════════════════════════
 * 对外暴露的接口（被 UploadDialog.vue 使用）
 * ═══════════════════════════════════════════════════════════════
 *   isUploading     boolean   是否正在上传
 *   progress        number    整体进度 0-100
 *   error           string    错误信息（空字符串 = 无错误）
 *   stage           string    当前阶段：hashing / init / uploading / completing
 *   hashingProgress string    计算指纹时的提示文案
 *   chunkProgress   object    { uploaded, total } 分片计数
 *   uploadFile(file, groupId) 上传入口
 *   reset()                   清空状态
 *   cancel()                  取消上传
 */
import { reactive, ref } from 'vue'
import {
  completeDocumentUpload,
  initDocumentUpload,
  uploadDocumentChunk,
} from '@/api/document'
import { extractApiError } from '@/api/http'

/** 每个分片的大小：4MB */
const CHUNK_SIZE = 4 * 1024 * 1024

/** 上传阶段 */
export type ChunkUploadStage = 'hashing' | 'init' | 'uploading' | 'completing'

/** 把 ArrayBuffer 转成十六进制字符串（后端要 hex 格式） */
function toHex(buffer: ArrayBuffer): string {
  return Array.from(new Uint8Array(buffer))
    .map((byte) => byte.toString(16).padStart(2, '0'))
    .join('')
}

/** 计算任意 Blob 的 SHA-256 指纹，返回十六进制字符串 */
async function sha256Hex(blob: Blob): Promise<string> {
  const buffer = await blob.arrayBuffer()
  const digest = await crypto.subtle.digest('SHA-256', buffer)
  return toHex(digest)
}

export function useChunkedUpload() {
  // ── 状态 ──────────────────────────────────────────
  const isUploading = ref(false)
  const progress = ref(0)
  const error = ref('')
  const stage = ref<ChunkUploadStage | ''>('')
  const hashingProgress = ref('')
  const chunkProgress = reactive({ uploaded: 0, total: 0 })

  // ── 内部：取消用 ───────────────────────────────────
  let abortController: AbortController | null = null

  /** 清空状态（保留 isUploading，由 uploadFile 自己管） */
  function reset() {
    progress.value = 0
    error.value = ''
    stage.value = ''
    hashingProgress.value = ''
    chunkProgress.uploaded = 0
    chunkProgress.total = 0
  }

  /** 上传主流程 */
  async function uploadFile(file: File, groupId: number): Promise<void> {
    reset()
    isUploading.value = true
    abortController = new AbortController()
    const signal = abortController.signal

    try {
      // ── 第 1 步：算文件指纹 ────────────────────────
      // 用途：① 秒传判断（后端看这个哈希有没有存过）② 上传会话的身份
      stage.value = 'hashing'
      hashingProgress.value = '正在读取文件…'
      const fileHash = await sha256Hex(file)
      if (signal.aborted) return

      const chunkCount = Math.ceil(file.size / CHUNK_SIZE)
      resetChunkProgress(chunkCount)

      // ── 第 2 步：初始化上传 ────────────────────────
      stage.value = 'init'
      const initResult = await initDocumentUpload(
        {
          groupId,
          fileName: file.name,
          fileSize: file.size,
          contentType: file.type || 'application/octet-stream',
          fileHash,
          chunkSize: CHUNK_SIZE,
          chunkCount,
        },
        signal,
      )

      // 秒传：后端已有相同文件，直接完成，不需要传任何分片
      if (initResult.instantUpload) {
        progress.value = 100
        return
      }

      if (!initResult.uploadId) {
        throw new Error('初始化上传失败：未返回上传会话 ID')
      }
      const uploadId = initResult.uploadId

      // 断点续传：后端可能已经存了一部分分片，跳过它们
      const alreadyUploaded = new Set(initResult.uploadedChunks ?? [])
      const effectiveChunkSize = initResult.chunkSize ?? CHUNK_SIZE
      const effectiveChunkCount = initResult.chunkCount ?? chunkCount
      resetChunkProgress(effectiveChunkCount, alreadyUploaded.size)

      // ── 第 3 步：逐片上传 ──────────────────────────
      stage.value = 'uploading'
      for (let index = 0; index < effectiveChunkCount; index += 1) {
        if (alreadyUploaded.has(index)) continue
        if (signal.aborted) return

        const start = index * effectiveChunkSize
        const blob = file.slice(start, Math.min(start + effectiveChunkSize, file.size))
        const chunkHash = await sha256Hex(blob)

        await uploadDocumentChunk(
          { uploadId, chunkIndex: index, chunkHash, chunk: blob },
          // 字节级进度回调：把「当前分片已传字节」换算成整体百分比
          (loadedBytes) => {
            const uploadedBytes = index * effectiveChunkSize + loadedBytes
            progress.value = Math.min(99, Math.round((uploadedBytes / file.size) * 100))
          },
          signal,
        )

        chunkProgress.uploaded += 1
        progress.value = Math.min(99, Math.round((chunkProgress.uploaded / effectiveChunkCount) * 100))
      }

      // ── 第 4 步：合并完成 ──────────────────────────
      stage.value = 'completing'
      await completeDocumentUpload(uploadId, signal)
      progress.value = 100
      stage.value = ''
    } catch (err) {
      stage.value = ''
      if ((err as Error)?.name === 'AbortError') {
        // 用户主动取消：不算错误，也不往外抛
        return
      }
      error.value = extractApiError(err, '分片上传失败')
      throw err
    } finally {
      isUploading.value = false
      abortController = null
    }
  }

  /** 取消上传 */
  function cancel() {
    if (!isUploading.value) return
    abortController?.abort()
    isUploading.value = false
    stage.value = ''
    progress.value = 0
    error.value = '已取消上传'
  }

  /** 内部：设置分片计数 */
  function resetChunkProgress(total: number, uploaded = 0) {
    chunkProgress.total = total
    chunkProgress.uploaded = uploaded
  }

  return {
    // 状态
    isUploading,
    progress,
    error,
    stage,
    hashingProgress,
    chunkProgress,
    // 方法
    uploadFile,
    reset,
    cancel,
  }
}
