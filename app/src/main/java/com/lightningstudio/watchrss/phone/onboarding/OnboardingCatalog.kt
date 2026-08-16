package com.lightningstudio.watchrss.phone.onboarding

/**
 * 24 步投入型引导目录。顺序即体验顺序，调整只需移动列表项。
 *
 * 心理学结构：
 * 1-2    欢迎 + 协议（同意勾选是最早的一次微小承诺）
 * 3-7    热场选择题（点选必答，投入成本极低但已经开始）
 * 8-13   思考输入（自由文本/数字，全部可跳过；跳过会在付费墙降级文案中兑现损失框架）
 * 14-17  魔术时刻（动画 + 真实导入任务，制造沉没成本与 IKEA 效应）
 * 18-20  登录（外部 AccountActivity，19-20 为虚拟计数步）
 * 21-24  授权说明 + 价值回放 + 付费前说明 + 完成（完成后由 MainActivity 的付费墙收口）
 */
val ONBOARDING_CATALOG: List<OnboardingStep> = listOf(
    // ── 阶段一：欢迎 ──────────────────────────────────────────────
    OnboardingStep(
        id = "welcome",
        type = StepType.WELCOME,
        title = "欢迎使用腕上RSS",
        body = "在手机管理阅读，在手表继续阅读。",
        detail = "接下来几步，带你定制专属的阅读计划——大约需要 3 分钟。"
    ),
    // ── 阶段二：协议（必须在任何输入之前，PIPL）─────────────────────
    OnboardingStep(
        id = "consent",
        type = StepType.CONSENT,
        title = "服务条款与隐私保护",
        body = "请在使用前阅读用户协议和隐私政策。未经同意，应用不会启动统计、云同步或账号授权联网。",
        detail = "政策说明了账号信息、内容同步、统计分析、第三方平台与 AI 功能涉及的数据处理。"
    ),
    // ── 阶段三：热场选择题（必答）──────────────────────────────────
    OnboardingStep(
        id = "scene",
        type = StepType.CHIP_MULTI,
        title = "你一般在什么场景下想刷点东西？",
        body = "选出最常发生的 1-3 个场景，计划会围绕它们定制。",
        options = listOf("通勤路上", "睡前躺平", "摸鱼间隙", "排队等候", "运动休息", "其他时间"),
        echoKey = "scene"
    ),
    OnboardingStep(
        id = "watch_ownership",
        type = StepType.CHIP_SINGLE,
        title = "你拥有一块智能手表吗？",
        body = "腕上RSS 的阅读会在手表上继续。",
        options = listOf("有 OPPO 手表", "有其他品牌手表", "还没有，先看看"),
        echoKey = "watch_ownership"
    ),
    OnboardingStep(
        id = "categories",
        type = StepType.CHIP_MULTI,
        title = "平时关注什么内容？",
        body = "多选几个，你的计划会优先照顾它们。",
        options = listOf("科技", "财经", "生活", "游戏", "人文", "健康", "体育", "其他"),
        echoKey = "categories"
    ),
    OnboardingStep(
        id = "current_daily",
        type = StepType.SLIDER,
        title = "现在每天大概读几篇文章？",
        body = "拖动滑块估算一下，不准确也没关系。",
        range = 0..20,
        echoKey = "current_daily"
    ),
    OnboardingStep(
        id = "platforms",
        type = StepType.CHIP_MULTI,
        title = "你平时在哪刷内容？",
        body = "这些平台的内容都可以装进你的资料库。",
        options = listOf("RSS 订阅", "网页文章", "哔哩哔哩", "抖音", "微信公众号", "播客"),
        echoKey = "platforms"
    ),
    // ── 阶段四：思考输入（可跳过；跳过 = 损失框架）──────────────────
    OnboardingStep(
        id = "favorite_website",
        type = StepType.TEXT,
        title = "写下你常看的第一个网站或订阅源",
        body = "比如某个博客、新闻站或 RSS 地址——之后导入文章时你会用到它。",
        detail = "这一步可以跳过，但写下后你的计划会更具体。",
        skippable = true,
        maxChars = 80,
        echoKey = "favorite_website"
    ),
    OnboardingStep(
        id = "unfinished_article",
        type = StepType.TEXT,
        title = "最近有哪篇文章没看完？",
        body = "写下它的标题或大意——两分钟后你会把它装进你的资料库。",
        skippable = true,
        maxChars = 80,
        echoKey = "unfinished_article"
    ),
    OnboardingStep(
        id = "daily_target",
        type = StepType.NUMBER,
        title = "给自己定个目标：每天读几篇？",
        body = "输入一个数字，再顺手写一句：为什么是这个数？",
        skippable = true,
        range = 1..50,
        echoKey = "daily_target"
    ),
    OnboardingStep(
        id = "why_read_more",
        type = StepType.TEXT,
        title = "为什么想用碎片时间读更多？",
        body = "写给自己看的一句话。它会一直出现在你的计划里。",
        detail = "比如：想戒掉无意识刷短视频；想跟上行业动态。",
        skippable = true,
        maxChars = 120,
        echoKey = "why_read_more"
    ),
    OnboardingStep(
        id = "plan_name",
        type = StepType.PLAN_NAME,
        title = "给你的阅读计划起个名字",
        body = "1-12 个字。这个名字会出现在你的计划页里。",
        skippable = true,
        maxChars = 12,
        echoKey = "plan_name"
    ),
    OnboardingStep(
        id = "commitment_days",
        type = StepType.NUMBER,
        title = "打算坚持多少天？",
        body = "写下你对这个计划的第一份承诺。",
        skippable = true,
        range = 1..365,
        echoKey = "commitment_days"
    ),
    // ── 阶段五：魔术时刻 ──────────────────────────────────────────
    OnboardingStep(
        id = "magic_moment",
        type = StepType.ANIMATION,
        title = "这就是魔术时刻",
        body = "一篇文章，从手机飞向你的手表。",
        detail = "在手机上导入的文章，会同步到已配对的手表继续阅读。"
    ),
    OnboardingStep(
        id = "first_import",
        type = StepType.IMPORT_URL,
        title = "把你的第一篇文章装进资料库",
        body = "粘贴你刚才想起的那篇文章链接，现在就导入它。",
        detail = "支持普通网页。导入后文章保存在本机资料库，并可通过蓝牙同步到手表。",
        skippable = true
    ),
    OnboardingStep(
        id = "import_result",
        type = StepType.IMPORT_RESULT,
        title = "第一篇文章已入库",
        body = "你的资料库已经开张了。",
        detail = "进入应用后，可以随时在资料库找到它。"
    ),
    OnboardingStep(
        id = "features",
        type = StepType.FEATURE_PREVIEW,
        title = "手腕上的完整能力",
        body = "腕上RSS 手表端支持这些能力——你的手机是它们的补给站。",
        detail = "手表端已支持连接手机同步；哔哩哔哩与抖音账号在手表端登录。"
    ),
    // ── 阶段六：登录与收尾 ─────────────────────────────────────────
    OnboardingStep(
        id = "login_guide",
        type = StepType.LOGIN_GUIDE,
        title = "登录腕上RSS账号",
        body = "登录后你的授权和云同步才有归属：换手机、多设备、数据恢复都靠它。",
        detail = "手机号验证码即可登录，大约 30 秒。"
    ),
    OnboardingStep(
        id = "login_virtual_1",
        type = StepType.LOGIN_VIRTUAL,
        title = "登录进行中…",
        body = "正在验证账号，请稍候。"
    ),
    OnboardingStep(
        id = "login_virtual_2",
        type = StepType.LOGIN_VIRTUAL,
        title = "登录进行中…",
        body = "正在验证账号，请稍候。"
    ),
    OnboardingStep(
        id = "auth_info",
        type = StepType.AUTH_INFO,
        title = "关于手机版授权",
        body = "手机版是一次性买断：¥6 为账号增加 3 台手机永久授权，不自动续费。",
        detail = "每台登录的手机占用 1 个名额；新设备挤占时，最早激活的那台会自动撤销。"
    ),
    OnboardingStep(
        id = "value_recap",
        type = StepType.VALUE_RECAP,
        title = "你的计划已就绪",
        body = "下面是你亲手定制的计划。",
        detail = "所有内容已保存在本机，下一步是解锁完整功能。"
    ),
    OnboardingStep(
        id = "payment_intro",
        type = StepType.PAYMENT_INTRO,
        title = "解锁手机版",
        body = "一次性支付 ¥6，为账号增加 3 台手机永久授权容量，不自动续费。支付成功后 7 天内可在订单页无理由全额退款。",
        detail = "勾选表示你已阅读并同意《腕上RSS手机版付费服务协议》。"
    ),
    OnboardingStep(
        id = "complete",
        type = StepType.COMPLETE,
        title = "一切就绪",
        body = "你的计划、文章与账号都已准备好。",
        detail = "接下来将校验本机授权，然后进入腕上RSS。"
    )
)

/** 目录不变量索引（0-based）。测试会锁定这些值。 */
object OnboardingCatalogIndices {
    val size: Int get() = ONBOARDING_CATALOG.size
    const val CONSENT_INDEX = 1
    const val LOGIN_GUIDE_INDEX = 17
    const val COMPLETE_INDEX = 23

    fun stepAt(index: Int): OnboardingStep = ONBOARDING_CATALOG[index]
}

/** 阶段标签，用于进度头。 */
fun onboardingPhaseLabel(stepIndex: Int): String = when {
    stepIndex < 2 -> "开始"
    stepIndex < 8 -> "了解你"
    stepIndex < 14 -> "定制计划"
    stepIndex < 18 -> "魔术时刻"
    else -> "登录与收尾"
}
