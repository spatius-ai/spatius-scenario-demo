package ai.spatialwalk.scenes

import android.content.Context
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import java.util.Locale

/**
 * UI copy and avatar lines.
 *
 * The lines follow the same language as the UI: what the avatar says and what the student
 * reads must match, and splitting them across two places would drift apart sooner or
 * later. The question bank lives here too — switching languages swaps a whole set of
 * questions rather than wrapping Chinese question text in an English shell. Organized the
 * same way as the Web and iOS clients.
 */
enum class Lang(val code: String) {
    ZH("zh"),
    EN("en"),
}

/**
 * The current language.
 *
 * Starts from the system language, then follows the user's last choice. On a phone the
 * system language is an unambiguous signal, unlike the web where it has to be guessed
 * from navigator.language.
 */
object Localization {

    private const val PREFS = "tutoring.lang"
    private const val KEY = "lang"

    /** Compose reads this to trigger recomposition, hence mutableState rather than a plain field. */
    var lang by mutableStateOf(Lang.ZH)
        private set

    private var prefsContext: Context? = null

    fun init(context: Context) {
        prefsContext = context.applicationContext
        val saved = prefs(context).getString(KEY, null)
        lang = when (saved) {
            "zh" -> Lang.ZH
            "en" -> Lang.EN
            else -> if (Locale.getDefault().language == "zh") Lang.ZH else Lang.EN
        }
    }

    fun select(value: Lang) {
        lang = value
        prefsContext?.let { prefs(it).edit { putString(KEY, value.code) } }
    }

    val t: Strings get() = if (lang == Lang.ZH) StringsZh else StringsEn

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

/** Lets Composables read the current copy. */
val LocalStrings = compositionLocalOf { StringsZh }

/** One set of copy. One instance per language, with matching fields. */
data class Strings(
    // Config screen
    val stepCredentials: String,
    val stepAvatar: String,
    val sectionBackendUrl: String,
    val sectionCredentials: String,
    val sectionAvatar: String,
    val sectionTtsModel: String,
    val sectionScene: String,
    val fieldBackendUrl: String,
    val fieldSampleRate: String,
    val backendUrlHint: String,
    val credentialsHint: String,
    val credentialConfigured: String,
    val credentialMissing: String,
    val testConnection: String,
    val connecting: String,
    val backendOnline: String,
    val connectFailedShort: (String) -> String,
    val loadConfigFailed: (String) -> String,
    val saveFailed: (String) -> String,
    val next: String,
    val start: String,
    val saving: String,
    val fillAllFirst: String,
    val sampleRateNote: String,
    val sceneTutoring: String,
    val sceneLive: String,
    val sceneService: String,
    val sceneCompanion: String,

    // Companion
    val enteringCompanion: String,
    val companionListening: String,
    val companionMicOff: String,
    val companionMemory: String,
    val companionMemoryEmpty: String,
    val companionMemoryRaw: String,
    val companionMemoryMeta: (Int, Int) -> String,
    val companionForget: String,
    val companionHello: String,
    val companionHelloAgain: String,
    val companionPick: String,
    val companionPickHint: String,
    val companionCustom: String,
    val companionCustomBlurb: String,
    val companionCustomPlaceholder: String,
    val companionStart: String,
    val companionLeave: String,

    // Bank support
    val enteringService: String,
    val serviceTitle: String,
    val serviceOnline: String,
    val serviceGreeting: String,
    val serviceAsk: String,
    val serviceSearch: String,
    val serviceSearchEmpty: String,
    val serviceLive: String,
    val serviceLiveOn: String,
    val serviceLiveListening: String,
    val serviceLiveOpen: String,
    val serviceCategories: String,
    val serviceSpeaking: String,
    val serviceLeave: String,

    // Live room
    val enteringLive: String,
    val liveBadge: String,
    val viewerCount: (Int) -> String,
    val greeting: String,
    val you: String,
    val chatPlaceholder: String,
    val send: String,
    val micIdle: String,
    val micPending: String,
    val micLive: String,
    val micWelcome: String,

    // Classroom
    val teacherComing: String,
    val section: (Int) -> String,
    val prevQuestion: String,
    val nextQuestion: String,
    val startFreeTalk: String,
    val endFreeTalk: String,
    val freeTalkListening: String,
    val freeTalkLocked: String,
    val teacher: String,
    val me: String,
    val micFailed: String,
    val cameraOn: String,
    val cameraOff: String,
    val stagePreparing: String,
    val stageLoadingAvatar: String,
    val stageDownloading: (Int) -> String,
    val stageConnecting: String,
    val stageConnected: String,
    val connectFailed: (String) -> String,
    val offlineWaiting: String,

    // Avatar lines
    val sayAllCorrect: String,
    val sayCorrectThenChat: String,
    val sayCorrect: String,
    val sayFinishFirst: String,
    val sayFreeTalkOpen: String,
    val wrongReplies: List<String>,

    /**
     * Builds the full text for the avatar to read aloud. The per-language phrasing
     * (Chinese "第一题", English "Question 1") lives in each instance; callers just take
     * the current language.
     */
    val readAloud: (String, List<String>, Int) -> String,

    val perfTitle: String,
    val perfFps: String,
    val perfPresentationFps: String,
    val perfJank: String,
    val perfFrameTime: String,
    val perfIntervalP95: String,
    val perfCpu: String,
    val perfPlayback: String,
    val perfFramesTotal: String,
    val perfDropped: String,
    val perfLost: String,
    val perfStarved: String,
    val perfWaiting: String,
    val perfNote: String,
    val perfDevice: String,
    val perfCpuTemp: String,
    val perfPower: String,
    val perfBatteryTemp: String,
)

val StringsZh = Strings(
    stepCredentials = "填凭据",
    stepAvatar = "选角色",
    sectionBackendUrl = "后台地址",
    sectionCredentials = "凭据",
    sectionAvatar = "角色",
    sectionTtsModel = "语音合成模型",
    sectionScene = "场景",
    fieldBackendUrl = "后台地址",
    fieldSampleRate = "采样率",
    backendUrlHint = "在电脑上 `cd backend && python server.py`，启动时终端会打印一行「手机端配置页填这个地址」，照着填。手机够不到开发机的 localhost，必须是局域网地址。",
    credentialsHint = "这些在电脑上填，不用在手机上敲：编辑 `backend/.env`，或打开 http://localhost:5180 用网页的配置页填（那里有各家控制台的截图指引）。填完回到这里点「测试连接」刷新状态。",
    credentialConfigured = "已配置",
    credentialMissing = "未配置",
    testConnection = "测试连接",
    connecting = "连接中…",
    backendOnline = "后台在线",
    connectFailedShort = { "连不上：$it" },
    loadConfigFailed = { "读取后台配置失败：$it" },
    saveFailed = { "保存失败：$it" },
    next = "下一步",
    start = "开始",
    saving = "保存中…",
    fillAllFirst = "凭据还没配全，先在电脑上填完",
    sampleRateNote = "选控制台里那个 TTS 的采样率（见上图）。面板里没有这一项的（如 OpenAI）保持 24000 即可——两边对不上时数字人有画面但不出声，且不会报错。",
    sceneTutoring = "辅导课堂",
    sceneLive = "直播间",
    sceneService = "银行客服",
    sceneCompanion = "陪伴",

    enteringCompanion = "正在进入房间…",
    companionListening = "正在听，点击静音",
    companionMicOff = "已静音，点击说话",
    companionMemory = "记得的事",
    companionMemoryEmpty = "还没有记住什么。聊过之后，下次就会记得。",
    companionMemoryRaw = "这次聊的内容已经记下了，等攒够一些会整理成一段。",
    companionMemoryMeta = { turns, size -> "$turns 条对话 · $size 字" },
    companionForget = "清空记忆",
    companionHello = "你来啦，坐吧。今天想聊点什么都行。",
    companionHelloAgain = "你来啦。上次聊的那些我都记着呢，今天怎么样？",
    companionPick = "今天想和谁聊聊",
    companionPickHint = "选一个，或者自己写一个。聊过的事会被记住。",
    companionCustom = "自己写一个",
    companionCustomBlurb = "描述这个角色是谁、怎么说话",
    companionCustomPlaceholder = "比如：你是我大学时的同桌，说话很直，喜欢讲冷笑话，知道我一直想去看极光…",
    companionStart = "开始",
    companionLeave = "离开房间",

    enteringService = "正在接入客服…",
    serviceTitle = "数字客户经理",
    serviceOnline = "在线",
    // Points at the panel below rather than the Web version's column on the right: the
    // phone layout puts the topics under the avatar, and telling someone to look right
    // where there is nothing is worse than saying nothing.
    serviceGreeting = "您好，我是您的数字客户经理，很高兴为您服务。" +
        "您可以在下方选择服务类型，也可以直接搜索，或者点实时问答开口问我。",
    serviceAsk = "猜您想问",
    serviceSearch = "搜索您想了解的问题",
    serviceSearchEmpty = "没有找到相关问题，换个说法试试",
    serviceLive = "实时问答",
    serviceLiveOn = "正在聆听，点击结束",
    serviceLiveListening = "正在聆听，您说，我听着呢",
    serviceLiveOpen = "好的，您直接问就行，我在听。",
    serviceCategories = "请选择服务类型",
    serviceSpeaking = "正在回答…",
    serviceLeave = "结束会话",

    enteringLive = "正在进入直播间…",
    liveBadge = "直播中",
    viewerCount = { "$it 人在看" },
    greeting = "哈喽大家好呀，欢迎来到我的直播间，今天咱们就随便聊聊天，有什么想问的都可以打在公屏上。",
    you = "我",
    chatPlaceholder = "说点什么…",
    send = "发送",
    micIdle = "申请连麦",
    micPending = "正在申请…",
    micLive = "连麦中",
    micWelcome = "好，麦克风给你啦，你说吧，我听着呢。",

    teacherComing = "老师正在赶来…",
    section = { "第 $it 节" },
    prevQuestion = "上一题",
    nextQuestion = "下一题",
    startFreeTalk = "开始自由问答",
    endFreeTalk = "结束自由问答",
    freeTalkListening = "正在聆听，有什么不懂的都可以问我",
    freeTalkLocked = "答完全部题目后可以开始自由问答",
    teacher = "老师",
    me = "我",
    micFailed = "麦克风开启失败",
    cameraOn = "开启摄像头",
    cameraOff = "关闭摄像头",
    stagePreparing = "正在准备课堂…",
    stageLoadingAvatar = "正在加载数字人…",
    stageDownloading = { "下载中 $it%" },
    stageConnecting = "正在连接…",
    stageConnected = "已连接",
    connectFailed = { "连接失败：$it" },
    offlineWaiting = "网络连接不可用，等待恢复…",

    sayAllCorrect = "真棒，全部答对了。接下来是自由问答环节，有什么不懂的都可以问我。",
    sayCorrectThenChat = "真棒，你全部答对了，还有什么想交流的吗？",
    sayCorrect = "真棒，恭喜你答对了",
    sayFinishFirst = "请先答完试题，我们再开始自由讨论哦",
    sayFreeTalkOpen = "好，我们随便聊聊，有什么不懂的都可以问我。",
    wrongReplies = listOf(
        "再想想看",
        "不对哦，换个思路试试",
        "这个不太对，再看看题目",
        "差一点，再仔细想想",
        "距离正确答案已经很接近了！",
    ),

    // TTS reads "第 1 题" inconsistently, so map to Chinese numerals; fall back to Arabic
    // digits beyond the table. Options are separated by "。" so they aren't run together;
    // the "选项" prefix gives TTS the context it needs, otherwise the letter A gets read
    // as "啊".
    readAloud = { stem, options, number ->
        val table = listOf("一", "二", "三", "四", "五", "六", "七", "八", "九", "十")
        val n = if (number in 1..10) table[number - 1] else number.toString()
        buildString {
            append("第${n}题。$stem")
            options.forEachIndexed { index, option ->
                append("。选项 ${'A' + index}，$option")
            }
        }
    },

    perfTitle = "性能",
    perfFps = "渲染帧率",
    perfPresentationFps = "显示帧率",
    perfJank = "卡顿占比",
    perfFrameTime = "平均帧耗时",
    perfIntervalP95 = "帧间隔 P95",
    perfCpu = "CPU 占用",
    perfPlayback = "播放（本次会话累计）",
    perfFramesTotal = "总帧数",
    perfDropped = "丢弃",
    perfLost = "丢失",
    perfStarved = "缓冲不足",
    perfWaiting = "等待数字人说话…",
    perfNote = "数字人说话时约 25 帧/秒，静止时不产生新帧。",
    perfDevice = "设备",
    perfCpuTemp = "CPU 温度",
    perfPower = "功耗",
    perfBatteryTemp = "电池温度",
)

val StringsEn = Strings(
    stepCredentials = "Credentials",
    stepAvatar = "Avatar",
    sectionBackendUrl = "Backend",
    sectionCredentials = "Credentials",
    sectionAvatar = "Avatar",
    sectionTtsModel = "Speech model",
    sectionScene = "Scene",
    fieldBackendUrl = "Backend URL",
    fieldSampleRate = "Sample rate",
    backendUrlHint = "On your computer run `cd backend && python server.py`. It prints the address to use here on startup. A phone cannot reach the computer's localhost, so this must be a LAN address.",
    credentialsHint = "Fill these in on your computer, not here: edit `backend/.env`, or open http://localhost:5180 and use the web config page (it has screenshots for each console). Then come back and tap Test connection.",
    credentialConfigured = "Set",
    credentialMissing = "Missing",
    testConnection = "Test connection",
    connecting = "Connecting…",
    backendOnline = "Backend is up",
    connectFailedShort = { "Cannot reach it: $it" },
    loadConfigFailed = { "Could not read backend config: $it" },
    saveFailed = { "Save failed: $it" },
    next = "Next",
    start = "Start",
    saving = "Saving…",
    fillAllFirst = "Some credentials are still missing — fill them in on your computer",
    sampleRateNote = "Pick whatever the TTS in the console reports (see the image). Leave it at 24000 for providers that do not expose one, such as OpenAI — a mismatch leaves the avatar rendering but silent, and nothing reports an error.",
    sceneTutoring = "Tutoring classroom",
    sceneLive = "Live room",
    sceneService = "Bank support",
    sceneCompanion = "Companion",

    enteringCompanion = "Coming through…",
    companionListening = "Listening — tap to mute",
    companionMicOff = "Muted — tap to talk",
    companionMemory = "What is remembered",
    companionMemoryEmpty = "Nothing yet. Talk for a while and it will be remembered next time.",
    companionMemoryRaw = "This conversation is saved. It gets folded into notes once there is enough of it.",
    companionMemoryMeta = { turns, size -> "$turns turns · $size characters" },
    companionForget = "Forget everything",
    companionHello = "Oh, hello. Come and sit down — we can talk about anything you like.",
    companionHelloAgain = "You are back. I still remember what we talked about last time. How have you been?",
    companionPick = "Who do you feel like talking to?",
    companionPickHint = "Pick one, or write your own. They will remember what you talk about.",
    companionCustom = "Write your own",
    companionCustomBlurb = "Say who they are and how they talk",
    companionCustomPlaceholder = "For example: you sat next to me all through university, you are very blunt, you tell terrible jokes, and you know I have always wanted to see the northern lights…",
    companionStart = "Start",
    companionLeave = "Leave the room",

    enteringService = "Connecting you to support…",
    serviceTitle = "Digital account manager",
    serviceOnline = "Online",
    // Points at the panel below rather than the Web version's column on the right: the
    // phone layout puts the topics under the avatar, and telling someone to look right
    // where there is nothing is worse than saying nothing.
    serviceGreeting = "Hello, I am your digital account manager and I am glad to help. " +
        "Pick a topic below, search for something specific, or tap Ask me anything and just ask.",
    serviceAsk = "You might want to ask",
    serviceSearch = "Search for a question",
    serviceSearchEmpty = "Nothing matched — try different wording",
    serviceLive = "Ask me anything",
    serviceLiveOn = "Listening — tap to stop",
    serviceLiveListening = "Listening — go ahead, I am here",
    serviceLiveOpen = "Go ahead and ask — I am listening.",
    serviceCategories = "What can I help with?",
    serviceSpeaking = "Answering…",
    serviceLeave = "End chat",

    enteringLive = "Entering the live room…",
    liveBadge = "LIVE",
    viewerCount = { "$it watching" },
    greeting = "Hey everyone, welcome to the stream. We are just hanging out and chatting today, so put anything you want to ask in the chat.",
    you = "You",
    chatPlaceholder = "Say something…",
    send = "Send",
    micIdle = "Request mic",
    micPending = "Requesting…",
    micLive = "On mic",
    micWelcome = "Alright, the mic is yours — go ahead, I am listening.",

    teacherComing = "Your teacher is on the way…",
    section = { "Section $it" },
    prevQuestion = "Previous",
    nextQuestion = "Next",
    startFreeTalk = "Start free talk",
    endFreeTalk = "End free talk",
    freeTalkListening = "Listening — ask me anything you're unsure about",
    freeTalkLocked = "Answer every question first to unlock free talk",
    teacher = "Teacher",
    me = "Me",
    micFailed = "Could not turn on the microphone",
    cameraOn = "Turn camera on",
    cameraOff = "Turn camera off",
    stagePreparing = "Setting up the classroom…",
    stageLoadingAvatar = "Loading the avatar…",
    stageDownloading = { "Downloading $it%" },
    stageConnecting = "Connecting…",
    stageConnected = "Connected",
    connectFailed = { "Connection failed: $it" },
    offlineWaiting = "No network connection — waiting for it to come back…",

    sayAllCorrect = "Well done, all correct. Now it's free talk time — ask me anything you're unsure about.",
    sayCorrectThenChat = "Well done, you got them all. Anything else you'd like to talk about?",
    sayCorrect = "Nice work, that's correct",
    sayFinishFirst = "Let's finish the questions first, then we can chat freely",
    sayFreeTalkOpen = "Sure, let's chat. Ask me anything you're unsure about.",
    wrongReplies = listOf(
        "Have another think",
        "Not quite — try a different approach",
        "That's not it, take another look at the question",
        "So close, think it through once more",
        "You're very nearly there!",
    ),

    readAloud = { stem, options, number ->
        buildString {
            append("Question $number. $stem")
            options.forEachIndexed { index, option ->
                append(". Option ${'A' + index}, $option")
            }
        }
    },

    perfTitle = "Performance",
    perfFps = "Render FPS",
    perfPresentationFps = "Display FPS",
    perfJank = "Jank",
    perfFrameTime = "Avg frame time",
    perfIntervalP95 = "Frame interval P95",
    perfCpu = "CPU",
    perfPlayback = "Playback (session total)",
    perfFramesTotal = "Frames",
    perfDropped = "Dropped",
    perfLost = "Lost",
    perfStarved = "Starved",
    perfWaiting = "Waiting for the avatar to speak…",
    perfNote = "Around 25 fps while the avatar speaks; no new frames while it is still.",
    perfDevice = "Device",
    perfCpuTemp = "CPU temp",
    perfPower = "Power",
    perfBatteryTemp = "Battery temp",
)
