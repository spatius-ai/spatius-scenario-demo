import Foundation

/// The live room's audience: viewers, what they type, and the gifts they send.
///
/// A chat-and-hangout stream, not a shopping one — the host is there to talk to whoever
/// shows up. Most of what scrolls past is short and low-content: greetings, someone
/// arriving, a reaction, a question thrown out on the way past. That is what a real room
/// looks like, and it is also what makes the few lines the host picks up feel chosen.
///
/// Every message carries its own reply. The avatar reads that reply verbatim through
/// `speak`, which takes about a second, where routing the text through the conversational
/// LLM would take three to four — far too slow for a room where lines arrive faster than
/// once a second.
///
/// Plenty of messages have no reply at all, and that is the point: a host who answers
/// every single line reads as a bot.
///
/// Same content as the Web and Android clients; changes belong in all three.

struct Viewer {
    let name: String
    /// Hue for the generated avatar, so one viewer keeps a colour across their messages.
    let hue: Double
}

struct Gift: Identifiable {
    var id: String { name }
    let name: String
    let icon: String
    /// Coin value, shown on the button and on the message.
    let value: Int
}

struct ChatMessage: Identifiable {
    let id: Int
    let viewer: Viewer
    let text: String
    /// What the host could say back, or nil for the ones that go unanswered.
    let reply: String?
    /// Set on gift messages, which are styled apart and always worth a thank-you.
    let gift: Gift?
}

private struct Line {
    let text: String
    let reply: String?
    init(_ text: String, _ reply: String?) {
        self.text = text
        self.reply = reply
    }
}

enum ChatData {

    private static let namesZh = [
        "小鱼干",
        "奶茶三分糖",
        "今天也要早睡",
        "晚风",
        "柠檬不酸",
        "大脸猫",
        "一颗草莓",
        "路人甲",
        "风筝与线",
        "橘子汽水",
        "半糖主义",
        "木木",
        "追光者",
        "咸鱼翻身",
        "云朵",
        "不吃香菜",
        "深夜放毒",
        "一只鸽子",
        "雨天不打伞",
        "打工人"
    ]

    private static let namesEn = [
        "pixelcat",
        "late_night_tea",
        "not_a_robot",
        "seabreeze",
        "lemonzest",
        "bigface",
        "strawberry",
        "passerby",
        "kite_string",
        "orangepop",
        "halfsugar",
        "mumu",
        "lightchaser",
        "flyingfish",
        "cloudy",
        "no_cilantro",
        "midnight_snack",
        "a_pigeon",
        "no_umbrella",
        "nine_to_five"
    ]

    /// The message bank, each line paired with the reply it would get.
    ///
    /// Weighted towards the short and unanswerable on purpose. In a real room most of the
    /// screen is 「哈哈哈」 and people saying hello, and a bank where every line is a
    /// well-formed question makes the audience read as scripted.
    private static let scriptZh = [
        Line("来了来了", nil),
        Line("主播好", "你好呀，欢迎"),
        Line("哈哈哈哈哈", nil),
        Line("主播今天状态不错", "谢谢，今天心情确实挺好"),
        Line("刚下班过来的", "辛苦啦，坐下歇会儿"),
        Line("？？？", nil),
        Line("主播唱一个", "今天嗓子一般，下次给你唱"),
        Line("这个背景是哪儿", "就在家里，随便布置的"),
        Line("好听", nil),
        Line("主播多大了", "这个是秘密，猜猜看"),
        Line("第一次来", "欢迎新朋友，常来玩"),
        Line("前排", nil),
        Line("主播声音好好听", "谢谢，你耳朵真好"),
        Line("晚上吃的什么", "随便煮了点面，你呢"),
        Line("哇", nil),
        Line("主播平时都干嘛", "也没干嘛，上班下班，偶尔出来聊聊天"),
        Line("关注了", "谢谢关注，明天差不多这个点还在"),
        Line("路过看看", nil),
        Line("主播明天还播吗", "播的，明天老时间"),
        Line("有没有推荐的电影", "最近看了个挺好哭的，等下讲给你听"),
        Line("来晚了", "不晚不晚，刚开始没多久"),
        Line("666", nil),
        Line("主播冷不冷", "还行，屋里开着暖气呢"),
        Line("我也是", nil),
        Line("主播讲讲你的猫", "它现在正睡在我脚边上呢"),
        Line("好看", nil),
        Line("这歌叫什么名字", "等我找一下，一会儿发出来"),
        Line("主播加油", "谢谢支持"),
        Line("在的在的", nil),
        Line("主播休息一下吧", "好，那我喝口水"),
    ]

    private static let scriptEn = [
        Line("just got here", nil),
        Line("hey there", "Hi, welcome in"),
        Line("hahahaha", nil),
        Line("you seem in a good mood today", "I am, thanks for noticing"),
        Line("came straight from work", "Long day? Sit down and relax"),
        Line("???", nil),
        Line("sing something", "My voice is not up to it tonight, next time"),
        Line("where is that background", "Just my place, nothing fancy"),
        Line("nice", nil),
        Line("how old are you", "That is a secret, have a guess"),
        Line("first time here", "Welcome, glad you found us"),
        Line("front row", nil),
        Line("love your voice", "Thank you, you have a good ear"),
        Line("what did you have for dinner", "Just noodles, nothing exciting. You?"),
        Line("whoa", nil),
        Line("what do you do normally", "Not much, work, then come here and talk"),
        Line("followed", "Thanks for the follow, same time tomorrow"),
        Line("just passing by", nil),
        Line("streaming tomorrow?", "I am, usual time"),
        Line("any film recommendations", "Watched a real tearjerker recently, I will tell you about it"),
        Line("sorry I am late", "Not late at all, only just started"),
        Line("lol", nil),
        Line("are you cold", "I am fine, heating is on"),
        Line("same", nil),
        Line("tell us about your cat", "She is asleep by my feet right now"),
        Line("pretty", nil),
        Line("what song is this", "Give me a second, I will post the name"),
        Line("you got this", "Thank you for the support"),
        Line("still here", nil),
        Line("take a break", "Alright, let me get some water"),
    ]

    private static let giftsZh = [
        Gift(name: "小心心", icon: "💗", value: 1),
        Gift(name: "棒棒糖", icon: "🍭", value: 5),
        Gift(name: "鲜花", icon: "💐", value: 10),
        Gift(name: "蛋糕", icon: "🎂", value: 30),
        Gift(name: "跑车", icon: "🏎️", value: 100),
        Gift(name: "城堡", icon: "🏰", value: 520),
    ]

    private static let giftsEn = [
        Gift(name: "Heart", icon: "💗", value: 1),
        Gift(name: "Lollipop", icon: "🍭", value: 5),
        Gift(name: "Flowers", icon: "💐", value: 10),
        Gift(name: "Cake", icon: "🎂", value: 30),
        Gift(name: "Sports car", icon: "🏎️", value: 100),
        Gift(name: "Castle", icon: "🏰", value: 520),
    ]

    private static var nextId = 1

    static func gifts(for lang: Lang) -> [Gift] {
        lang == .zh ? giftsZh : giftsEn
    }

    static func randomViewer(for lang: Lang) -> Viewer {
        Viewer(
            name: (lang == .zh ? namesZh : namesEn).randomElement() ?? "",
            hue: Double.random(in: 0..<360)
        )
    }

    static func randomGift(for lang: Lang) -> Gift {
        gifts(for: lang).randomElement()!
    }

    /// A viewer message with the reply it would get already attached.
    static func randomChat(for lang: Lang) -> ChatMessage {
        let line = (lang == .zh ? scriptZh : scriptEn).randomElement()!
        defer { nextId += 1 }
        return ChatMessage(
            id: nextId,
            viewer: randomViewer(for: lang),
            text: line.text,
            reply: line.reply,
            gift: nil
        )
    }

    /// A gift, which always draws a thank-you naming both the giver and what they sent.
    static func giftMessage(for lang: Lang, gift: Gift, from viewer: Viewer? = nil) -> ChatMessage {
        let sender = viewer ?? randomViewer(for: lang)
        // The thank-you carries its own excitement in the words. `speak` takes text and
        // nothing else — no emotion, rate or pitch — so exclamations and interjections are
        // the only handle on how animated it comes out, and TTS does lift its delivery for
        // them.
        let thanks: [String] = lang == .zh
            ? [
                "哇！谢谢 \(sender.name) 的\(gift.name)！太感谢啦！",
                "\(sender.name) 送了个\(gift.name)！谢谢你呀，你也太好了吧！",
                "哎呀 \(sender.name)，\(gift.name)收到啦，谢谢谢谢！",
            ]
            : [
                "Whoa, thank you \(sender.name) for the \(gift.name)! That is so kind!",
                "\(sender.name) sent a \(gift.name)! Thank you so much, you are the best!",
                "Oh wow, a \(gift.name) from \(sender.name)! Thank you!",
            ]
        defer { nextId += 1 }
        return ChatMessage(
            id: nextId,
            viewer: sender,
            text: lang == .zh ? "送出了\(gift.name)" : "sent a \(gift.name)",
            reply: thanks.randomElement(),
            gift: gift
        )
    }
}
