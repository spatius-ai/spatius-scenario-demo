/**
 * UI copy and the avatar's spoken lines.
 *
 * The lines follow the same language as the UI: what the avatar reads out and what the
 * student sees have to match, and splitting them across two places would drift apart
 * sooner or later. The question bank lives here too — switching languages swaps a whole
 * question set rather than wrapping a Chinese stem in an English shell.
 */
import { ref, computed } from 'vue'

export type Lang = 'zh' | 'en'

const STORAGE_KEY = 'tutoring.lang'

/**
 * Last choice if there is one, English otherwise.
 *
 * Deliberately not following navigator.language: this demo is read mostly by developers
 * outside China, and a browser set to Chinese for unrelated reasons should not decide
 * what a public demo opens in. One click switches it.
 */
function initialLang(): Lang {
  const saved = localStorage.getItem(STORAGE_KEY)
  if (saved === 'zh' || saved === 'en') return saved
  return 'en'
}

export const lang = ref<Lang>(initialLang())

export function setLang(next: Lang): void {
  lang.value = next
  localStorage.setItem(STORAGE_KEY, next)
  document.documentElement.lang = next === 'zh' ? 'zh-CN' : 'en'
}

// Set on first load as well as on change: screen readers and browser translation both
// read <html lang>.
document.documentElement.lang = lang.value === 'zh' ? 'zh-CN' : 'en'

interface Strings {
  /** Config page */
  stepCredentials: string
  stepTeacher: string
  fillAllFirst: string
  next: string
  backendDown: string
  loadingConfig: string
  sectionSpatius: string
  sectionLivekit: string
  sectionAgora: string
  sectionTransport: string
  transportLivekit: string
  transportAgora: string
  agoraPipelineNote: string
  agoraGuideAlt1: string
  agoraGuideAlt2: string
  agoraGuideAlt3: string
  agoraGuideAlt4: string
  agoraGuideAlt5: string
  agoraVoiceGuideAlt: string
  agoraAsrGuideAlt: string
  agoraAsrNote: string
  guideAlt: string
  livekitGuideAlt1: string
  livekitGuideAlt2: string
  livekitGuideCaption: string
  guideCaption: string
  sectionAvatar: string
  sectionScene: string
  sceneTutoring: string
  sceneLive: string
  sceneService: string
  sceneCompanion: string

  // Companion
  enteringCompanion: string
  companionListening: string
  companionMicOff: string
  companionMemory: string
  companionMemoryEmpty: string
  companionMemoryRaw: string
  companionMemoryMeta: (turns: number, size: number) => string
  companionForget: string
  companionHello: string
  companionHelloAgain: string
  companionPick: string
  companionPickHint: string
  companionCustom: string
  companionCustomBlurb: string
  companionCustomPlaceholder: string
  companionStart: string

  // Customer service
  enteringService: string
  serviceTitle: string
  serviceOnline: string
  serviceGreeting: string
  serviceAsk: string
  serviceSearch: string
  serviceSearchEmpty: string
  serviceLive: string
  serviceLiveOn: string
  serviceLiveListening: string
  serviceLiveOpen: string
  serviceCategories: string
  serviceSpeaking: string
  serviceLeave: string

  // Live room
  enteringLive: string
  liveBadge: string
  viewerCount: (n: number) => string
  giftBar: string
  music: string
  leaveLive: string
  greeting: string
  you: string
  chatPlaceholder: string
  send: string
  micIdle: string
  micPending: string
  micLive: string
  micWelcome: string
  fieldAvatar: string
  fieldTtsModel: string
  preview: string
  voiceCantonese: string
  voiceMale: string
  voiceFemale: string
  start: string
  saving: string

  /** Field labels */
  fieldApiKey: string
  fieldAppId: string
  fieldAppCertificate: string
  fieldLlmUrl: string
  fieldLlmApiKey: string
  fieldLlmModel: string
  fieldTtsAppId: string
  fieldTtsToken: string
  fieldLivekitUrl: string
  fieldPipelineId: string
  fieldSampleRate: string
  sampleRateNote: string
  fieldApiSecret: string

  /** Connection stages, shown on the classroom's waiting overlay */
  stagePreparing: string
  stageLoadingAvatar: string
  stageDownloading: (percent: number) => string
  stageConnecting: string
  stageConnected: string

  /** Config read and write failures */
  configReadFailed: (status: number) => string
  configSaveFailed: (status: number) => string

  /** Classroom */
  teacherComing: string
  connectFailed: (reason: string) => string
  /** LiveKit only: the room connected but the agent worker never joined it. The Agora
   *  path deliberately carries on without its agent, so it never shows this. */
  agentMissing: string
  back: string
  section: (n: number) => string
  prevQuestion: string
  nextQuestion: string
  startFreeTalk: string
  endFreeTalk: string
  freeTalkListening: string
  freeTalkLocked: string
  teacher: string
  me: string
  micFailed: string
  cameraOn: string
  cameraOff: string

  /** The avatar's spoken lines */
  sayAllCorrect: string
  sayCorrectThenChat: string
  sayCorrect: string
  sayFinishFirst: string
  sayFreeTalkOpen: string
  wrongReplies: string[]

  /** Reading a question aloud */
  readAloud: (stem: string, options: string[], number: number) => string

  /** Performance readout */
  perfTitle: string
  perfFps: string
  perfPresentationFps: string
  perfJank: string
  perfFrameTime: string
  perfIntervalP95: string
  perfCpu: string
  perfPlayback: string
  perfFramesTotal: string
  perfDropped: string
  perfSkipped: string
  perfStarved: string
  perfWaiting: string
  perfNote: string
}

const zh: Strings = {
  stepCredentials: '填凭据',
  stepTeacher: '选角色',
  fillAllFirst: '每一项都填完才能继续',
  next: '下一步',
  backendDown: '后台是否已启动？',
  loadingConfig: '正在读取后台配置…',
  sectionSpatius: 'Spatius',
  sectionLivekit: 'LiveKit',
  sectionAgora: 'Agora',
  sectionTransport: '接入方式',
  transportLivekit: 'LiveKit',
  transportAgora: 'Agora',
  agoraPipelineNote: '点开图片跳转 Agora 控制台。Projects 里选项目，复制 App ID 与主要证书（证书要先开启）；Agents 里建好 agent，点 Publish 后从 Code 面板取 pipeline id。',
  agoraGuideAlt1: '在 Agora 控制台的 Projects 里选一个项目',
  agoraGuideAlt2: '项目凭据面板里复制 App ID 与主要证书',
  agoraGuideAlt3: '在 Agents 列表里找到自己建的 agent',
  agoraGuideAlt4: '先点 Publish，再从右侧 Code 面板取 pipeline id',
  agoraGuideAlt5: '在 Models → TTS 里核对采样率',
  agoraVoiceGuideAlt: '在 Agora 控制台的 Agents → Models 里换 TTS 与音色',
  agoraAsrGuideAlt: '声网控制台里的语音识别配置',
  agoraAsrNote: '后台按界面语言下发语音识别配置（厂商、模型、凭据），默认对应上图这套 Deepgram nova-3。控制台里换了识别模型的话，backend/agora.py 顶部那几个常量也要跟着改——两边对不上时识别会失灵，且不会报错。',
  guideAlt: '在 Spatius 控制台的 API Key 页面获取应用 ID 与 API Key',
  guideCaption: '点开图片跳转控制台。App ID 与 API Key 在左侧 Developer → API Key。',
  livekitGuideAlt1: 'LiveKit 控制台左下角进入 Settings',
  livekitGuideAlt2: 'Settings 里的 API keys 页面',
  livekitGuideCaption: '点开图片跳转 LiveKit Cloud。先进 Settings，再开 API keys；Secret 只在创建时显示一次。',
  sectionAvatar: '角色',
  sectionScene: '场景',
  sceneTutoring: '辅导课堂',
  sceneLive: '直播间',
  sceneService: '银行客服',
  sceneCompanion: '陪伴',

  enteringCompanion: '正在进入房间…',
  companionListening: '正在听，点击静音',
  companionMicOff: '已静音，点击说话',
  companionMemory: '记得的事',
  companionMemoryEmpty: '还没有记住什么。聊过之后，下次就会记得。',
  companionMemoryRaw: '这次聊的内容已经记下了，等攒够一些会整理成一段。',
  companionMemoryMeta: (turns: number, size: number) => `${turns} 条对话 · ${size} 字`,
  companionForget: '清空记忆',
  companionHello: '你来啦，坐吧。今天想聊点什么都行。',
  companionHelloAgain: '你来啦。上次聊的那些我都记着呢，今天怎么样？',
  companionPick: '今天想和谁聊聊',
  companionPickHint: '选一个，或者自己写一个。聊过的事会被记住。',
  companionCustom: '自己写一个',
  companionCustomBlurb: '描述这个角色是谁、怎么说话',
  companionCustomPlaceholder: '比如：你是我大学时的同桌，说话很直，喜欢讲冷笑话，知道我一直想去看极光…',
  companionStart: '开始',

  enteringService: '正在接入客服…',
  serviceTitle: '数字客户经理',
  serviceOnline: '在线',
  serviceGreeting:
    '您好，我是您的数字客户经理，很高兴为您服务。您可以选择服务类型，也可以直接搜索，或者点实时问答开口问我。',
  serviceAsk: '猜您想问',
  serviceSearch: '搜索您想了解的问题',
  serviceSearchEmpty: '没有找到相关问题，换个说法试试',
  serviceLive: '实时问答',
  serviceLiveOn: '正在聆听，点击结束',
  serviceLiveListening: '正在聆听，您说，我听着呢',
  serviceLiveOpen: '好的，您直接问就行，我在听。',
  serviceCategories: '请选择服务类型',
  serviceSpeaking: '正在回答…',
  serviceLeave: '结束会话',

  enteringLive: '正在进入直播间…',
  liveBadge: '直播中',
  viewerCount: (n: number) => `${n} 人在看`,
  giftBar: '送礼物',
  music: '背景音乐',
  leaveLive: '退出直播间',
  greeting: '哈喽大家好呀，欢迎来到我的直播间，今天咱们就随便聊聊天，有什么想问的都可以打在公屏上。',
  you: '我',
  chatPlaceholder: '说点什么…',
  send: '发送',
  micIdle: '申请连麦',
  micPending: '正在申请…',
  micLive: '连麦中，点击挂断',
  micWelcome: '好，麦克风给你啦，你说吧，我听着呢。',
  fieldAvatar: '形象',
  fieldTtsModel: '语音合成模型',
  preview: '试听',
  voiceCantonese: '粤语',
  voiceMale: '男声',
  voiceFemale: '女声',
  start: '开始',
  saving: '保存中…',

  // Left in English on purpose. These are said as the English abbreviations even when
  // speaking Chinese, and translating them would both read as something nobody says and
  // make them harder to match against the field names in the consoles.
  fieldApiKey: 'API Key',
  fieldAppId: 'App ID',
  fieldAppCertificate: 'App Certificate',
  fieldLlmUrl: 'LLM URL',
  fieldLlmApiKey: 'LLM API Key',
  fieldLlmModel: 'LLM Model',
  fieldTtsAppId: 'TTS App ID',
  fieldTtsToken: 'TTS Token',
  fieldLivekitUrl: 'Server URL',
  fieldApiSecret: 'API Secret',
  fieldPipelineId: 'Pipeline ID',
  fieldSampleRate: '采样率',
  sampleRateNote: '选控制台里那个 TTS 的采样率（见右图）。面板里没有这一项的（如 OpenAI）保持 24000 即可——两边对不上时数字人有画面但不出声，且不会报错。',

  stagePreparing: '正在准备课堂…',
  stageLoadingAvatar: '正在加载数字人…',
  stageDownloading: (percent) => `下载中 ${percent}%`,
  stageConnecting: '正在连接…',
  stageConnected: '已连接',

  configReadFailed: (status) => `读取后台配置失败：HTTP ${status}`,
  configSaveFailed: (status) => `保存后台配置失败：HTTP ${status}`,

  teacherComing: '老师正在赶来…',
  connectFailed: (reason) => `连接失败：${reason}`,
  agentMissing:
    '已连接房间，但对话服务(agent worker)没有加入。\n\n' +
    '角色画面由本地渲染，与此无关；这里只影响说话。\n\n' +
    'worker 跑在本机，请检查后端终端：\n' +
    '1. server.py 是否启动成功 —— 端口 8787 被占用时它会直接退出\n' +
    '2. worker 有没有报错或 traceback(崩溃后不会自动重启)\n' +
    '3. ps aux | grep agent.py 确认 worker 进程还在',
  back: '返回',
  section: (n) => `第 ${n} 节`,
  prevQuestion: '上一题',
  nextQuestion: '下一题',
  startFreeTalk: '开始自由问答',
  endFreeTalk: '结束自由问答',
  freeTalkListening: '正在聆听，有什么不懂的都可以问我',
  freeTalkLocked: '答完全部题目后可以开始自由问答',
  teacher: '老师',
  me: '我',
  micFailed: '麦克风开启失败',
  cameraOn: '开启摄像头',
  cameraOff: '关闭摄像头',

  sayAllCorrect: '真棒，全部答对了。接下来是自由问答环节，有什么不懂的都可以问我。',
  sayCorrectThenChat: '真棒，你全部答对了，还有什么想交流的吗？',
  sayCorrect: '真棒，恭喜你答对了',
  sayFinishFirst: '请先答完试题，我们再开始自由讨论哦',
  sayFreeTalkOpen: '好，我们随便聊聊，有什么不懂的都可以问我。',
  wrongReplies: [
    '再想想看',
    '不对哦，换个思路试试',
    '这个不太对，再看看题目',
    '差一点，再仔细想想',
    '距离正确答案已经很接近了！',
  ],

  // TTS reads 「第 1 题」 inconsistently, so numbers are mapped to Chinese numerals;
  // anything out of range falls back to Arabic digits. Options are separated by 「。」 so
  // they are not run together, and the 「选项」 prefix gives TTS the context it needs —
  // without it the letter A is read as 「啊」.
  readAloud: (stem, options, number) => {
    const table = ['一', '二', '三', '四', '五', '六', '七', '八', '九', '十']
    const n = number >= 1 && number <= 10 ? table[number - 1] : String(number)
    let out = `第${n}题。${stem}`
    options.forEach((option, index) => {
      out += `。选项 ${String.fromCharCode(65 + index)}，${option}`
    })
    return out
  },

  perfTitle: '性能',
  perfFps: '渲染帧率',
  perfPresentationFps: '显示帧率',
  perfJank: '卡顿占比',
  perfFrameTime: '平均帧耗时',
  perfIntervalP95: '帧间隔 P95',
  perfCpu: 'CPU 占用',
  perfPlayback: '播放（本次会话累计）',
  perfFramesTotal: '总帧数',
  perfDropped: '丢弃',
  perfSkipped: '跳过',
  perfStarved: '缓冲不足',
  perfWaiting: '等待数字人说话…',
  perfNote: '数字人说话时约 25 帧/秒，静止时不产生新帧。',
}

const en: Strings = {
  stepCredentials: 'Credentials',
  stepTeacher: 'Avatar',
  fillAllFirst: 'Fill in every field to continue',
  next: 'Next',
  backendDown: 'Is the backend running?',
  loadingConfig: 'Reading backend settings…',
  sectionSpatius: 'Spatius',
  sectionLivekit: 'LiveKit',
  sectionAgora: 'Agora',
  sectionTransport: 'Transport',
  transportLivekit: 'LiveKit',
  transportAgora: 'Agora',
  agoraPipelineNote: 'Opens the Agora console. Pick a project under Projects and copy the App ID and primary certificate (enable it first); build an agent under Agents, publish it, then take the pipeline id from the Code panel.',
  agoraGuideAlt1: 'Pick a project under Projects in the Agora console',
  agoraGuideAlt2: 'Copy the App ID and primary certificate from the credentials panel',
  agoraGuideAlt3: 'Find your agent in the Agents list',
  agoraGuideAlt4: 'Publish first, then take the pipeline id from the Code panel',
  agoraGuideAlt5: 'Check the sample rate under Models → TTS',
  agoraVoiceGuideAlt: 'Change the TTS model and voice under Agents → Models in the Agora console',
  agoraAsrGuideAlt: 'Speech recognition settings in the Agora console',
  agoraAsrNote: 'The backend sends recognition settings (vendor, model, credential) to match the UI language, defaulting to the Deepgram nova-3 setup shown above. If you change the recognition model in the console, change the constants at the top of backend/agora.py to match — a mismatch stops recognition working, and nothing reports an error.',
  guideAlt: 'Where to find your App ID and API Key in the Spatius console',
  guideCaption: 'Opens the console. Find both under Developer → API Key in the sidebar.',
  livekitGuideAlt1: 'Open Settings from the LiveKit sidebar',
  livekitGuideAlt2: 'The API keys page inside Settings',
  livekitGuideCaption: 'Opens LiveKit Cloud. Go to Settings, then API keys — the secret is shown only once, when you create it.',
  sectionAvatar: 'Avatar',
  sectionScene: 'Scene',
  sceneTutoring: 'Tutoring classroom',
  sceneLive: 'Live room',
  sceneService: 'Bank support',
  sceneCompanion: 'Companion',

  enteringCompanion: 'Coming through…',
  companionListening: 'Listening — tap to mute',
  companionMicOff: 'Muted — tap to talk',
  companionMemory: 'What is remembered',
  companionMemoryEmpty: 'Nothing yet. Talk for a while and it will be remembered next time.',
  companionMemoryRaw: 'This conversation is saved. It gets folded into notes once there is enough of it.',
  companionMemoryMeta: (turns: number, size: number) => `${turns} turns · ${size} characters`,
  companionForget: 'Forget everything',
  companionHello: 'Oh, hello. Come and sit down — we can talk about anything you like.',
  companionHelloAgain: 'You are back. I still remember what we talked about last time. How have you been?',
  companionPick: 'Who do you feel like talking to?',
  companionPickHint: 'Pick one, or write your own. They will remember what you talk about.',
  companionCustom: 'Write your own',
  companionCustomBlurb: 'Say who they are and how they talk',
  companionCustomPlaceholder: 'For example: you sat next to me all through university, you are very blunt, you tell terrible jokes, and you know I have always wanted to see the northern lights…',
  companionStart: 'Start',

  enteringService: 'Connecting you to support…',
  serviceTitle: 'Digital account manager',
  serviceOnline: 'Online',
  serviceGreeting:
    'Hello, I am your digital account manager and I am glad to help. Pick a topic, search for something specific, or tap Ask me anything and just ask.',
  serviceAsk: 'You might want to ask',
  serviceSearch: 'Search for a question',
  serviceSearchEmpty: 'Nothing matched — try different wording',
  serviceLive: 'Ask me anything',
  serviceLiveOn: 'Listening — tap to stop',
  serviceLiveListening: 'Listening — go ahead, I am here',
  serviceLiveOpen: 'Go ahead and ask — I am listening.',
  serviceCategories: 'What can I help with?',
  serviceSpeaking: 'Answering…',
  serviceLeave: 'End chat',

  enteringLive: 'Entering the live room…',
  liveBadge: 'LIVE',
  viewerCount: (n: number) => `${n} watching`,
  giftBar: 'Send a gift',
  music: 'Background music',
  leaveLive: 'Leave',
  greeting: "Hey everyone, welcome to the stream. We are just hanging out and chatting today, so put anything you want to ask in the chat.",
  you: 'You',
  chatPlaceholder: 'Say something…',
  send: 'Send',
  micIdle: 'Request mic',
  micPending: 'Requesting…',
  micLive: 'On mic — tap to hang up',
  micWelcome: "Alright, the mic is yours — go ahead, I'm listening.",
  fieldAvatar: 'Character',
  fieldTtsModel: 'Speech model',
  preview: 'Preview',
  voiceCantonese: 'Cantonese',
  voiceMale: 'Male',
  voiceFemale: 'Female',
  start: 'Start',
  saving: 'Saving…',

  // Same as the Chinese set: these are the field names as they appear in each console,
  // copied verbatim so they can be matched against them.
  fieldApiKey: 'API Key',
  fieldAppId: 'App ID',
  fieldAppCertificate: 'App Certificate',
  fieldLlmUrl: 'LLM URL',
  fieldLlmApiKey: 'LLM API Key',
  fieldLlmModel: 'LLM Model',
  fieldTtsAppId: 'TTS App ID',
  fieldTtsToken: 'TTS Token',
  fieldLivekitUrl: 'Server URL',
  fieldApiSecret: 'API Secret',
  fieldPipelineId: 'Pipeline ID',
  fieldSampleRate: 'Sample rate',
  sampleRateNote: 'Pick whatever the TTS in the console reports (see the image). Leave it at 24000 for providers that do not expose one, such as OpenAI — a mismatch leaves the avatar rendering but silent, and nothing reports an error.',

  stagePreparing: 'Setting up the classroom…',
  stageLoadingAvatar: 'Loading the avatar…',
  stageDownloading: (percent) => `Downloading ${percent}%`,
  stageConnecting: 'Connecting…',
  stageConnected: 'Connected',

  configReadFailed: (status) => `Couldn’t read backend settings: HTTP ${status}`,
  configSaveFailed: (status) => `Couldn’t save backend settings: HTTP ${status}`,

  teacherComing: 'Your teacher is on the way…',
  connectFailed: (reason) => `Couldn’t connect: ${reason}`,
  agentMissing:
    'Connected to the room, but the agent worker never joined.\n\n' +
    'The avatar itself renders locally and is unaffected; this only stops it talking.\n\n' +
    'The worker runs on your machine. Check the backend terminal:\n' +
    '1. Did server.py start? It exits immediately if port 8787 is taken\n' +
    '2. Any error or traceback from the worker (a crashed one is not restarted)\n' +
    '3. Run ps aux | grep agent.py to confirm the worker is still running',
  back: 'Back',
  section: (n) => `Section ${n}`,
  prevQuestion: 'Previous',
  nextQuestion: 'Next',
  startFreeTalk: 'Start open questions',
  endFreeTalk: 'End open questions',
  freeTalkListening: 'Listening — ask me anything you’re unsure about',
  freeTalkLocked: 'Answer every question to unlock open questions',
  teacher: 'Teacher',
  me: 'Me',
  micFailed: 'Could not turn on the microphone',
  cameraOn: 'Turn camera on',
  cameraOff: 'Turn camera off',

  sayAllCorrect:
    'Well done, you got them all right. Now it’s open questions — ask me anything you’re unsure about.',
  sayCorrectThenChat: 'Well done, you got them all right. Anything else you’d like to talk about?',
  sayCorrect: 'Nice work, that’s correct!',
  sayFinishFirst: 'Let’s finish the questions first, then we can chat.',
  sayFreeTalkOpen: 'Sure, let’s chat. Ask me anything you’re unsure about.',
  wrongReplies: [
    'Have another think.',
    'Not quite — try a different approach.',
    'That’s not it. Read the question once more.',
    'So close. Think it through again.',
    'You’re very nearly there!',
  ],

  readAloud: (stem, options, number) => {
    let out = `Question ${number}. ${stem}`
    options.forEach((option, index) => {
      out += `. Option ${String.fromCharCode(65 + index)}, ${option}`
    })
    return out
  },

  perfTitle: 'Performance',
  perfFps: 'Render FPS',
  perfPresentationFps: 'Display FPS',
  perfJank: 'Jank',
  perfFrameTime: 'Avg frame time',
  perfIntervalP95: 'Frame interval P95',
  perfCpu: 'CPU',
  perfPlayback: 'Playback (session total)',
  perfFramesTotal: 'Frames',
  perfDropped: 'Dropped',
  perfSkipped: 'Skipped',
  perfStarved: 'Starved',
  perfWaiting: 'Waiting for the avatar to speak…',
  perfNote: 'Around 25 fps while the avatar speaks; no new frames while it is still.',
}

const TABLE: Record<Lang, Strings> = { zh, en }

/** Copy for the current language. Templates use `t.xxx`. */
export const t = computed(() => TABLE[lang.value])
