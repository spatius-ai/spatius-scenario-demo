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
 */
import type { Lang } from './i18n'

export interface Viewer {
  name: string
  /** Hue for the generated avatar, so one viewer keeps a colour across their messages. */
  hue: number
}

export interface Gift {
  name: string
  icon: string
  /** Coin value, shown on the button and on the message. */
  value: number
}

export interface ChatMessage {
  id: number
  viewer: Viewer
  text: string
  /** What the host could say back, or null for the ones that go unanswered. */
  reply: string | null
  /** Set on gift messages, which are styled apart and always worth a thank-you. */
  gift: Gift | null
}

const NAMES: Record<Lang, readonly string[]> = {
  zh: [
    '小鱼干', '奶茶三分糖', '今天也要早睡', '晚风', '柠檬不酸',
    '大脸猫', '一颗草莓', '路人甲', '风筝与线', '橘子汽水',
    '半糖主义', '木木', '追光者', '咸鱼翻身', '云朵',
    '不吃香菜', '深夜放毒', '一只鸽子', '雨天不打伞', '打工人',
  ],
  en: [
    'pixelcat', 'late_night_tea', 'not_a_robot', 'seabreeze', 'lemonzest',
    'bigface', 'strawberry', 'passerby', 'kite_string', 'orangepop',
    'halfsugar', 'mumu', 'lightchaser', 'flyingfish', 'cloudy',
    'no_cilantro', 'midnight_snack', 'a_pigeon', 'no_umbrella', 'nine_to_five',
  ],
}

/**
 * The message bank, each line paired with the reply it would get.
 *
 * Weighted towards the short and unanswerable on purpose. In a real room most of the
 * screen is 「哈哈哈」 and people saying hello, and a bank where every line is a
 * well-formed question makes the audience read as scripted.
 */
const SCRIPT: Record<Lang, ReadonlyArray<{ text: string; reply: string | null }>> = {
  zh: [
    { text: '来了来了', reply: null },
    { text: '主播好', reply: '你好呀，欢迎' },
    { text: '哈哈哈哈哈', reply: null },
    { text: '主播今天状态不错', reply: '谢谢，今天心情确实挺好' },
    { text: '刚下班过来的', reply: '辛苦啦，坐下歇会儿' },
    { text: '？？？', reply: null },
    { text: '主播唱一个', reply: '今天嗓子一般，下次给你唱' },
    { text: '这个背景是哪儿', reply: '就在家里，随便布置的' },
    { text: '好听', reply: null },
    { text: '主播多大了', reply: '这个是秘密，猜猜看' },
    { text: '第一次来', reply: '欢迎新朋友，常来玩' },
    { text: '前排', reply: null },
    { text: '主播声音好好听', reply: '谢谢，你耳朵真好' },
    { text: '晚上吃的什么', reply: '随便煮了点面，你呢' },
    { text: '哇', reply: null },
    { text: '主播平时都干嘛', reply: '也没干嘛，上班下班，偶尔出来聊聊天' },
    { text: '关注了', reply: '谢谢关注，明天差不多这个点还在' },
    { text: '路过看看', reply: null },
    { text: '主播明天还播吗', reply: '播的，明天老时间' },
    { text: '有没有推荐的电影', reply: '最近看了个挺好哭的，等下讲给你听' },
    { text: '来晚了', reply: '不晚不晚，刚开始没多久' },
    { text: '666', reply: null },
    { text: '主播冷不冷', reply: '还行，屋里开着暖气呢' },
    { text: '我也是', reply: null },
    { text: '主播讲讲你的猫', reply: '它现在正睡在我脚边上呢' },
    { text: '好看', reply: null },
    { text: '这歌叫什么名字', reply: '等我找一下，一会儿发出来' },
    { text: '主播加油', reply: '谢谢支持' },
    { text: '在的在的', reply: null },
    { text: '主播休息一下吧', reply: '好，那我喝口水' },
  ],
  en: [
    { text: 'just got here', reply: null },
    { text: 'hey there', reply: 'Hi, welcome in' },
    { text: 'hahahaha', reply: null },
    { text: 'you seem in a good mood today', reply: 'I am, thanks for noticing' },
    { text: 'came straight from work', reply: 'Long day? Sit down and relax' },
    { text: '???', reply: null },
    { text: 'sing something', reply: 'My voice is not up to it tonight, next time' },
    { text: 'where is that background', reply: 'Just my place, nothing fancy' },
    { text: 'nice', reply: null },
    { text: 'how old are you', reply: 'That is a secret, have a guess' },
    { text: 'first time here', reply: 'Welcome, glad you found us' },
    { text: 'front row', reply: null },
    { text: 'love your voice', reply: 'Thank you, you have a good ear' },
    { text: 'what did you have for dinner', reply: 'Just noodles, nothing exciting. You?' },
    { text: 'whoa', reply: null },
    { text: 'what do you do normally', reply: 'Not much, work, then come here and talk' },
    { text: 'followed', reply: 'Thanks for the follow, same time tomorrow' },
    { text: 'just passing by', reply: null },
    { text: 'streaming tomorrow?', reply: 'I am, usual time' },
    { text: 'any film recommendations', reply: 'Watched a real tearjerker recently, I will tell you about it' },
    { text: 'sorry I am late', reply: 'Not late at all, only just started' },
    { text: 'lol', reply: null },
    { text: 'are you cold', reply: 'I am fine, heating is on' },
    { text: 'same', reply: null },
    { text: 'tell us about your cat', reply: 'She is asleep by my feet right now' },
    { text: 'pretty', reply: null },
    { text: 'what song is this', reply: 'Give me a second, I will post the name' },
    { text: 'you got this', reply: 'Thank you for the support' },
    { text: 'still here', reply: null },
    { text: 'take a break', reply: 'Alright, let me get some water' },
  ],
}

const GIFTS: Record<Lang, readonly Gift[]> = {
  zh: [
    { name: '小心心', icon: '💗', value: 1 },
    { name: '棒棒糖', icon: '🍭', value: 5 },
    { name: '鲜花', icon: '💐', value: 10 },
    { name: '蛋糕', icon: '🎂', value: 30 },
    { name: '跑车', icon: '🏎️', value: 100 },
    { name: '城堡', icon: '🏰', value: 520 },
  ],
  en: [
    { name: 'Heart', icon: '💗', value: 1 },
    { name: 'Lollipop', icon: '🍭', value: 5 },
    { name: 'Flowers', icon: '💐', value: 10 },
    { name: 'Cake', icon: '🎂', value: 30 },
    { name: 'Sports car', icon: '🏎️', value: 100 },
    { name: 'Castle', icon: '🏰', value: 520 },
  ],
}

function pick<T>(items: readonly T[]): T {
  return items[Math.floor(Math.random() * items.length)]
}

export function giftCatalogue(lang: Lang): readonly Gift[] {
  return GIFTS[lang]
}

export function randomViewer(lang: Lang): Viewer {
  return { name: pick(NAMES[lang]), hue: Math.floor(Math.random() * 360) }
}

let nextId = 1

/** A viewer message with the reply it would get already attached. */
export function randomChat(lang: Lang): ChatMessage {
  const line = pick(SCRIPT[lang])
  return {
    id: nextId++,
    viewer: randomViewer(lang),
    text: line.text,
    reply: line.reply,
    gift: null,
  }
}

/** A gift, which always draws a thank-you naming both the giver and what they sent. */
export function giftMessage(lang: Lang, gift: Gift, viewer?: Viewer): ChatMessage {
  const from = viewer ?? randomViewer(lang)
  // The thank-you carries its own excitement in the words. `speak` takes text and
  // nothing else — no emotion, rate or pitch — so exclamations and interjections are the
  // only handle on how animated it comes out, and TTS does lift its delivery for them.
  const thanks =
    lang === 'zh'
      ? [
          `哇！谢谢 ${from.name} 的${gift.name}！太感谢啦！`,
          `${from.name} 送了个${gift.name}！谢谢你呀，你也太好了吧！`,
          `哎呀 ${from.name}，${gift.name}收到啦，谢谢谢谢！`,
        ]
      : [
          `Whoa, thank you ${from.name} for the ${gift.name}! That is so kind!`,
          `${from.name} sent a ${gift.name}! Thank you so much, you are the best!`,
          `Oh wow, a ${gift.name} from ${from.name}! Thank you!`,
        ]
  return {
    id: nextId++,
    viewer: from,
    text: lang === 'zh' ? `送出了${gift.name}` : `sent a ${gift.name}`,
    reply: pick(thanks),
    gift,
  }
}

export function randomGift(lang: Lang): Gift {
  return pick(GIFTS[lang])
}
