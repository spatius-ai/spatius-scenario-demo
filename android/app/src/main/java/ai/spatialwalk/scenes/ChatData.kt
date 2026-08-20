package ai.spatialwalk.scenes

/**
 * The live room's audience: viewers, what they type, and the gifts they send.
 *
 * A chat-and-hangout stream, not a shopping one — the host is there to talk to whoever
 * shows up. Most of what scrolls past is short and low-content: greetings, someone
 * arriving, a reaction, a question thrown out on the way past. That is what a real room
 * looks like, and it is also what makes the few lines the host picks up feel chosen.
 *
 * Every message carries its own reply. The avatar reads that reply verbatim through
 * `speak`, which takes about a second, where routing the text through the conversational
 * LLM would take three to four — far too slow for a room where lines arrive faster than
 * once a second.
 *
 * Plenty of messages have no reply at all, and that is the point: a host who answers
 * every single line reads as a bot.
 *
 * Same content as the Web client's `chat-data.ts`; changes belong in both.
 */

data class Viewer(
    val name: String,
    /** Hue for the generated avatar, so one viewer keeps a colour across their messages. */
    val hue: Float,
)

data class Gift(
    val name: String,
    val icon: String,
    /** Coin value, shown on the button and on the message. */
    val value: Int,
)

data class ChatMessage(
    val id: Long,
    val viewer: Viewer,
    val text: String,
    /** What the host could say back, or null for the ones that go unanswered. */
    val reply: String?,
    /** Set on gift messages, which are styled apart and always worth a thank-you. */
    val gift: Gift?,
)

private val NAMES_ZH = listOf(
    "小鱼干", "奶茶三分糖", "今天也要早睡", "晚风", "柠檬不酸",
    "大脸猫", "一颗草莓", "路人甲", "风筝与线", "橘子汽水",
    "半糖主义", "木木", "追光者", "咸鱼翻身", "云朵",
    "不吃香菜", "深夜放毒", "一只鸽子", "雨天不打伞", "打工人",
)

private val NAMES_EN = listOf(
    "pixelcat", "late_night_tea", "not_a_robot", "seabreeze", "lemonzest",
    "bigface", "strawberry", "passerby", "kite_string", "orangepop",
    "halfsugar", "mumu", "lightchaser", "flyingfish", "cloudy",
    "no_cilantro", "midnight_snack", "a_pigeon", "no_umbrella", "nine_to_five",
)

/**
 * The message bank, each line paired with the reply it would get.
 *
 * Weighted towards the short and unanswerable on purpose. In a real room most of the
 * screen is 「哈哈哈」 and people saying hello, and a bank where every line is a
 * well-formed question makes the audience read as scripted.
 */
private val SCRIPT_ZH = listOf(
    "来了来了" to null,
    "主播好" to "你好呀，欢迎",
    "哈哈哈哈哈" to null,
    "主播今天状态不错" to "谢谢，今天心情确实挺好",
    "刚下班过来的" to "辛苦啦，坐下歇会儿",
    "？？？" to null,
    "主播唱一个" to "今天嗓子一般，下次给你唱",
    "这个背景是哪儿" to "就在家里，随便布置的",
    "好听" to null,
    "主播多大了" to "这个是秘密，猜猜看",
    "第一次来" to "欢迎新朋友，常来玩",
    "前排" to null,
    "主播声音好好听" to "谢谢，你耳朵真好",
    "晚上吃的什么" to "随便煮了点面，你呢",
    "哇" to null,
    "主播平时都干嘛" to "也没干嘛，上班下班，偶尔出来聊聊天",
    "关注了" to "谢谢关注，明天差不多这个点还在",
    "路过看看" to null,
    "主播明天还播吗" to "播的，明天老时间",
    "有没有推荐的电影" to "最近看了个挺好哭的，等下讲给你听",
    "来晚了" to "不晚不晚，刚开始没多久",
    "666" to null,
    "主播冷不冷" to "还行，屋里开着暖气呢",
    "我也是" to null,
    "主播讲讲你的猫" to "它现在正睡在我脚边上呢",
    "好看" to null,
    "这歌叫什么名字" to "等我找一下，一会儿发出来",
    "主播加油" to "谢谢支持",
    "在的在的" to null,
    "主播休息一下吧" to "好，那我喝口水",
)

private val SCRIPT_EN = listOf(
    "just got here" to null,
    "hey there" to "Hi, welcome in",
    "hahahaha" to null,
    "you seem in a good mood today" to "I am, thanks for noticing",
    "came straight from work" to "Long day? Sit down and relax",
    "???" to null,
    "sing something" to "My voice is not up to it tonight, next time",
    "where is that background" to "Just my place, nothing fancy",
    "nice" to null,
    "how old are you" to "That is a secret, have a guess",
    "first time here" to "Welcome, glad you found us",
    "front row" to null,
    "love your voice" to "Thank you, you have a good ear",
    "what did you have for dinner" to "Just noodles, nothing exciting. You?",
    "whoa" to null,
    "what do you do normally" to "Not much, work, then come here and talk",
    "followed" to "Thanks for the follow, same time tomorrow",
    "just passing by" to null,
    "streaming tomorrow?" to "I am, usual time",
    "any film recommendations" to "Watched a real tearjerker recently, I will tell you about it",
    "sorry I am late" to "Not late at all, only just started",
    "lol" to null,
    "are you cold" to "I am fine, heating is on",
    "same" to null,
    "tell us about your cat" to "She is asleep by my feet right now",
    "pretty" to null,
    "what song is this" to "Give me a second, I will post the name",
    "you got this" to "Thank you for the support",
    "still here" to null,
    "take a break" to "Alright, let me get some water",
)

private val GIFTS_ZH = listOf(
    Gift("小心心", "💗", 1),
    Gift("棒棒糖", "🍭", 5),
    Gift("鲜花", "💐", 10),
    Gift("蛋糕", "🎂", 30),
    Gift("跑车", "🏎️", 100),
    Gift("城堡", "🏰", 520),
)

private val GIFTS_EN = listOf(
    Gift("Heart", "💗", 1),
    Gift("Lollipop", "🍭", 5),
    Gift("Flowers", "💐", 10),
    Gift("Cake", "🎂", 30),
    Gift("Sports car", "🏎️", 100),
    Gift("Castle", "🏰", 520),
)

object ChatData {

    private var nextId = 1L

    fun gifts(lang: Lang): List<Gift> = if (lang == Lang.ZH) GIFTS_ZH else GIFTS_EN

    fun randomViewer(lang: Lang): Viewer =
        Viewer(
            name = (if (lang == Lang.ZH) NAMES_ZH else NAMES_EN).random(),
            hue = (0..359).random().toFloat(),
        )

    fun randomGift(lang: Lang): Gift = gifts(lang).random()

    /** A viewer message with the reply it would get already attached. */
    fun randomChat(lang: Lang): ChatMessage {
        val (text, reply) = (if (lang == Lang.ZH) SCRIPT_ZH else SCRIPT_EN).random()
        return ChatMessage(nextId++, randomViewer(lang), text, reply, null)
    }

    /** A gift, which always draws a thank-you naming both the giver and what they sent. */
    fun giftMessage(lang: Lang, gift: Gift, viewer: Viewer? = null): ChatMessage {
        val from = viewer ?: randomViewer(lang)
        // The thank-you carries its own excitement in the words. `speak` takes text and
        // nothing else — no emotion, rate or pitch — so exclamations and interjections
        // are the only handle on how animated it comes out, and TTS does lift its
        // delivery for them.
        val thanks = if (lang == Lang.ZH) {
            listOf(
                "哇！谢谢 ${from.name} 的${gift.name}！太感谢啦！",
                "${from.name} 送了个${gift.name}！谢谢你呀，你也太好了吧！",
                "哎呀 ${from.name}，${gift.name}收到啦，谢谢谢谢！",
            )
        } else {
            listOf(
                "Whoa, thank you ${from.name} for the ${gift.name}! That is so kind!",
                "${from.name} sent a ${gift.name}! Thank you so much, you are the best!",
                "Oh wow, a ${gift.name} from ${from.name}! Thank you!",
            )
        }
        return ChatMessage(
            id = nextId++,
            viewer = from,
            text = if (lang == Lang.ZH) "送出了${gift.name}" else "sent a ${gift.name}",
            reply = thanks.random(),
            gift = gift,
        )
    }
}
