package com.lightningstudio.watchrss.phone.connection.bluetooth

/** Placeholder copy for coordinator review in T-BOOK-MANIFEST-RCA. */
internal object BookSyncWarningText {
    const val SUMMARY = "以下书籍未同步"
    const val MANIFEST = "书籍清单校验失败"
    const val FIELD = "书籍清单缺少字段或字段格式不兼容"
    const val TIME = "书籍清单的时间记录无效"
    const val DELETION = "书籍清单的删除章节记录无效"
    const val FINGERPRINT = "书籍正文指纹格式无效"
    const val BOOK_ID = "书籍编号格式无效"
    const val TITLE = "书籍清单缺少有效书名"
    const val COUNT_TYPE = "书籍章节数格式无效"
    const val INSTANCE = "手机版暂不支持此书的独立副本编号"
    const val IDENTITY = "书籍编号与正文指纹不一致"
    const val COUNT = "书籍章节数超出 1～100000"
    const val DUPLICATE = "手表返回重复的书籍编号"
    const val LOCAL_CONFLICT = "手机与手表记录的书籍身份信息不一致"
    const val MISSING = "手机缺少该书的完整正文"
    const val UNKNOWN = "手表请求的书籍不在手机本地清单"
    const val DELETED = "手表请求了手机已删除的书籍"
    const val DELETION_ORDER = "删除章节编号无序或重复"
    const val DELETION_RANGE = "删除章节编号超出目录范围"
    const val INSTANCE_ACTION = "请保留原书并在手表阅读；反馈编号和原因码，待兼容版本支持后再同步"
    const val PACKAGE = "书籍文件校验失败"
    const val UPDATE = "请更新手机和手表应用后重试；仍失败时请保留原书并反馈编号和原因码"
    const val REIMPORT = "请保留原书，从原始 TXT/EPUB 重新导入后同步"
    const val UNAVAILABLE = "请在手机补回原始 TXT/EPUB 后同步"
    const val UNKNOWN_TITLE = "未提供书名"

    fun format(issues:List<BookSyncIssue>):String? = issues.takeIf{it.isNotEmpty()}?.joinToString("；",prefix="$SUMMARY：") { issue ->
        val title=BookSyncIssue.displayTitle(issue.title)?:UNKNOWN_TITLE
        val cause=when(issue.reason) {
            "manifest_fingerprint_invalid"->FINGERPRINT
            "manifest_book_id_invalid"->BOOK_ID
            "manifest_title_type"->TITLE
            "manifest_chapter_count_type"->COUNT_TYPE
            "manifest_not_object","manifest_source_device_id_type","manifest_deleted_type","manifest_source_url_type","manifest_kind_type"->FIELD
            "manifest_deletion_clock_type","manifest_deletion_clock_range","manifest_deleted_at_type","manifest_deleted_at_range","manifest_updated_at_type","manifest_updated_at_range"->TIME
            "manifest_deleted_chapters_type","manifest_deleted_chapter_type"->DELETION
            "manifest_instance_identity_unsupported"->INSTANCE
            "manifest_identity_mismatch"->IDENTITY
            "manifest_chapter_count_range"->COUNT
            "manifest_duplicate_book_id"->DUPLICATE
            "manifest_local_identity_conflict"->LOCAL_CONFLICT
            "manifest_requested_body_unavailable"->MISSING
            "manifest_requested_unknown_book"->UNKNOWN
            "manifest_requested_deleted_book"->DELETED
            "manifest_deleted_chapter_order"->DELETION_ORDER
            "manifest_deleted_chapter_range"->DELETION_RANGE
            else->if(issue.code=="book_package_invalid")PACKAGE else MANIFEST
        }
        val action=when {
            issue.reason=="manifest_instance_identity_unsupported"->INSTANCE_ACTION
            issue.reason=="manifest_requested_body_unavailable"->UNAVAILABLE
            issue.code=="book_package_invalid"->REIMPORT
            else->UPDATE
        }
        "《$title》（${issue.bookIdPrefix}）：$cause（${issue.reason}）。$action"
    }
}
