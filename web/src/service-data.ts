/**
 * The bank customer service script: the categories on offer, the questions under each,
 * and the cards that come up alongside an answer.
 *
 * Every answer is written out here and read verbatim through `speak`. Bank replies have
 * to be accurate — an account-opening checklist invented by an LLM is worse than useless
 * — and canned text also answers in about a second where the conversational path takes
 * three or four. The mic button is the exception: that hands the whole persona to the
 * LLM and lets the customer ask anything.
 *
 * Two levels: a category is picked first, then a question inside it. A flat list long
 * enough to cover a bank's business is a wall nobody reads, and the categories are how
 * a real assistant opens.
 */
import type { Lang } from './i18n'

/** A card shown under the avatar alongside the spoken answer. */
export interface AnswerCard {
  /** Card heading, or empty for a card that is only a list. */
  title: string
  /** Paragraphs of body text, shown above the list. */
  body: string[]
  /** A call-to-action line, styled as a link. Purely decorative — it goes nowhere. */
  action: string | null
}

export interface ServiceQuestion {
  id: string
  /** Text on the row the customer taps. */
  label: string
  /** What the customer is taken to have asked, echoed into the transcript. */
  asked: string
  /** What the agent says. Read verbatim. */
  answer: string
  /** The card that comes up with the answer, or null for a spoken-only reply. */
  card: AnswerCard | null
}

export interface ServiceCategory {
  id: string
  label: string
  /** Shown under the label on the category row. */
  blurb: string
  icon: string
  questions: ServiceQuestion[]
}

const CATEGORIES: Record<Lang, ServiceCategory[]> = {
  zh: [
    {
      id: 'account',
      label: '账户服务',
      blurb: '开户、销户、账户状态',
      icon: '🏦',
      questions: [
        {
          id: 'open-account',
          label: '怎么开对公账户',
          asked: '怎么开对公账户？',
          answer:
            '对公账户可以线上预约开户，点击页面上的办理入口提交预约，也可以带上资料到我行网点办理。对公账户分为基本存款账户、一般存款账户、临时存款账户和专用存款账户四类，用途各不相同。',
          card: {
            title: '对公账户预约开户',
            body: ['携带相关资料前往我行网点，或在线提交预约。四类账户的开户资料略有差异。'],
            action: '点击这里，立即办理',
          },
        },
        {
          id: 'account-basic',
          label: '基本存款账户需要什么资料',
          asked: '基本存款账户需要什么资料？',
          answer:
            '基本存款账户是办理日常转账结算和现金收付的主办账户，一家单位只能开一个。需要营业执照正本、法定代表人身份证件，以及经办人身份证件和授权书。',
          card: {
            title: '基本存款账户',
            body: ['一家单位只能开立一个，可以办理现金支取。'],
            action: null,
          },
        },
        {
          id: 'account-general',
          label: '一般存款账户是做什么的',
          asked: '一般存款账户是做什么的？',
          answer:
            '一般存款账户用于办理借款转存和其他结算资金收付，可以存现金但不能支取现金。开户前需要先有基本存款账户。',
          card: {
            title: '一般存款账户',
            body: ['可转账、可缴存现金，不能支取现金。需先开立基本存款账户。'],
            action: null,
          },
        },
        {
          id: 'account-temp',
          label: '临时存款账户怎么开',
          asked: '临时存款账户怎么开？',
          answer:
            '临时存款账户用于临时机构或异地临时经营活动，有效期最长两年。除了常规开户资料，还需要提供证明临时经营期限的相关文件。',
          card: {
            title: '临时存款账户',
            body: ['适用于临时经营、异地施工等场景，有效期不超过两年。'],
            action: null,
          },
        },
        {
          id: 'account-special',
          label: '专用存款账户有什么用',
          asked: '专用存款账户有什么用？',
          answer:
            '专用存款账户用于对特定用途资金进行专项管理，比如社保基金、住房基金、党团费等。开户时需要额外提供资金专项用途的证明材料。',
          card: {
            title: '专用存款账户',
            body: ['用于特定用途资金的专项管理，需提供用途证明材料。'],
            action: null,
          },
        },
        {
          id: 'account-dormant',
          label: '账户变成久悬户怎么办',
          asked: '账户变成久悬户怎么办？',
          answer:
            '账户连续两年没有发生收付活动，且未欠银行债务的，会被转为久悬户。需要激活的话，带上开户资料和法定代表人证件到开户网点办理恢复手续就可以。',
          card: null,
        },
        {
          id: 'account-close',
          label: '怎么销户',
          asked: '怎么销户？',
          answer:
            '销户需要先结清账户内所有款项，交回未使用的重要空白凭证和开户许可证，再由经办人携带证件和授权书到开户网点办理。有贷款未结清的账户不能直接销户。',
          card: null,
        },
      ],
    },
    {
      id: 'card',
      label: '银行卡',
      blurb: '挂失、补卡、密码、激活',
      icon: '💳',
      questions: [
        {
          id: 'card-lost',
          label: '银行卡丢了怎么挂失',
          asked: '银行卡丢了怎么挂失？',
          answer:
            '卡片丢失可以先在手机银行里做临时冻结，马上生效。如果需要正式挂失并补办新卡，带上身份证到任意网点就可以办理，补卡当场就能拿到。',
          card: {
            title: '挂失与补卡',
            body: ['手机银行可即时冻结，正式挂失需本人持身份证到网点办理。'],
            action: '点击这里，立即冻结',
          },
        },
        {
          id: 'card-password',
          label: '忘记密码怎么重置',
          asked: '忘记银行卡密码怎么重置？',
          answer:
            '取款密码连续输错三次会被锁定，第二天零点自动解锁。要重置密码需要本人带身份证和银行卡到任意网点办理，不能代办，也不能在线上重置。',
          card: null,
        },
        {
          id: 'card-activate',
          label: '新卡怎么激活',
          asked: '新办的卡怎么激活？',
          answer:
            '网点柜台办理的卡当场就已激活。线上申请邮寄到家的卡，可以在手机银行的卡片管理里输入卡号和验证码激活，也可以到任意网点柜台激活。',
          card: null,
        },
        {
          id: 'card-annual-fee',
          label: '年费和小额账户管理费',
          asked: '银行卡有年费和小额账户管理费吗？',
          answer:
            '每人在我行可以申请一个账户免收年费和小额账户管理费。已经开通的账户可以在手机银行里自助申请减免，也可以到网点办理。',
          card: null,
        },
        {
          id: 'card-expired',
          label: '卡片到期了怎么换',
          asked: '银行卡到期了怎么换？',
          answer:
            '卡片有效期到期前一个月可以到任意网点换发新卡，原卡号保持不变，绑定的代扣代缴关系也不受影响。换卡当场完成，不收工本费。',
          card: null,
        },
      ],
    },
    {
      id: 'transfer',
      label: '转账汇款',
      blurb: '限额、手续费、到账时间',
      icon: '💸',
      questions: [
        {
          id: 'transfer-limit',
          label: '转账限额是多少',
          asked: '转账限额是多少？',
          answer:
            '手机银行默认单笔五万、单日二十万。如果需要调高，可以在手机银行的安全中心里自助调整，也可以到网点申请更高的额度。',
          card: {
            title: '转账限额',
            body: ['手机银行默认单笔 5 万元、单日 20 万元，可在安全中心自助调整。'],
            action: null,
          },
        },
        {
          id: 'transfer-fee',
          label: '转账手续费怎么收',
          asked: '转账手续费怎么收？',
          answer:
            '手机银行和网上银行办理的境内人民币转账目前全部免收手续费。柜台办理的跨行转账按转账金额的万分之五收取，最低两元、最高五十元。',
          card: null,
        },
        {
          id: 'transfer-arrival',
          label: '转账多久到账',
          asked: '转账多久到账？',
          answer:
            '本行转账实时到账。跨行转账可以选择实时、普通或次日到账，实时通常几分钟内到，普通两小时左右。选了次日到账的，在到账前可以撤销。',
          card: {
            title: '到账时间',
            body: ['本行实时到账；跨行可选实时、普通或次日到账。'],
            action: null,
          },
        },
        {
          id: 'transfer-wrong',
          label: '转错账了怎么办',
          asked: '转错账了怎么办？',
          answer:
            '如果选的是次日到账，在到账前可以在手机银行里自助撤销。已经到账的资金银行无权划回，需要联系收款方协商退回，协商不成的可以通过司法途径解决。',
          card: null,
        },
        {
          id: 'transfer-overseas',
          label: '怎么办理境外汇款',
          asked: '怎么办理境外汇款？',
          answer:
            '境外汇款需要本人到网点办理，携带身份证件和汇款用途的证明材料。个人每年有等值五万美元的便利化额度，超出部分需要提供交易背景材料。',
          card: null,
        },
      ],
    },
    {
      id: 'loan',
      label: '贷款业务',
      blurb: '利率、申请、还款',
      icon: '📄',
      questions: [
        {
          id: 'loan-rate',
          label: '现在的贷款利率是多少',
          asked: '现在的贷款利率是多少？',
          answer:
            '贷款利率会根据贷款品种、期限和你的信用情况浮动。个人住房贷款目前按照最新的 LPR 加点执行，具体的执行利率需要提交申请后由系统核定。',
          card: {
            title: '贷款利率',
            body: ['利率按 LPR 加点执行，实际执行利率以审批结果为准。'],
            action: '点击这里，查看详情',
          },
        },
        {
          id: 'loan-apply',
          label: '房贷怎么申请',
          asked: '房贷怎么申请？',
          answer:
            '可以先在手机银行里做额度预估，然后带上身份证、收入证明、购房合同和首付款凭证到网点提交申请。审批通常在收齐资料后的五到十个工作日内完成。',
          card: {
            title: '房贷申请材料',
            body: ['身份证件、收入证明、购房合同、首付款凭证。审批约 5 到 10 个工作日。'],
            action: '点击这里，预估额度',
          },
        },
        {
          id: 'loan-prepay',
          label: '可以提前还款吗',
          asked: '可以提前还款吗？',
          answer:
            '可以。放款满一年后提前还款不收违约金，不满一年的按提前还款金额的百分之一收取。可以在手机银行里预约，也可以到贷款经办网点办理。',
          card: null,
        },
        {
          id: 'loan-overdue',
          label: '逾期了会有什么影响',
          asked: '贷款逾期了会有什么影响？',
          answer:
            '逾期会产生罚息，并且会上报征信系统，影响后续的贷款和信用卡审批。如果是暂时的资金周转困难，建议尽快联系贷款经办网点说明情况，看能否协商还款安排。',
          card: null,
        },
      ],
    },
    {
      id: 'wealth',
      label: '理财投资',
      blurb: '产品、风险评估、赎回',
      icon: '📈',
      questions: [
        {
          id: 'wealth-risk',
          label: '风险评估怎么做',
          asked: '理财的风险评估怎么做？',
          answer:
            '风险承受能力评估可以在手机银行里线上完成，大约十道题，几分钟就能做完。评估结果有效期一年，到期后需要重新评估才能继续购买理财产品。',
          card: null,
        },
        {
          id: 'wealth-products',
          label: '有哪些理财产品',
          asked: '有哪些理财产品？',
          answer:
            '我行的理财产品按风险等级从低到高分为五级，涵盖现金管理类、固定收益类、混合类和权益类。具体可以购买的产品要根据你的风险评估结果来匹配。',
          card: {
            title: '理财产品',
            body: ['按风险等级分为五级，需先完成风险承受能力评估。'],
            action: '点击这里，查看在售产品',
          },
        },
        {
          id: 'wealth-redeem',
          label: '理财怎么赎回',
          asked: '理财产品怎么赎回？',
          answer:
            '开放式产品在开放日可以随时在手机银行里发起赎回，资金通常在一到三个工作日内到账。封闭式产品在存续期内不能提前赎回，需要等到期自动兑付。',
          card: null,
        },
        {
          id: 'wealth-deposit',
          label: '定期存款利率',
          asked: '定期存款利率是多少？',
          answer:
            '定期存款利率按存期长短分档，具体挂牌利率可以在手机银行的存款页面查看。大额存单的利率通常高于同期定期存款，起存金额二十万元。',
          card: null,
        },
      ],
    },
    {
      id: 'ebank',
      label: '电子银行',
      blurb: '手机银行、网银、安全',
      icon: '📱',
      questions: [
        {
          id: 'ebank-register',
          label: '手机银行怎么开通',
          asked: '手机银行怎么开通？',
          answer:
            '下载我行手机银行 App 后，用本人的银行卡和预留手机号就可以自助注册。如果没有预留手机号或者手机号已经变更，需要先到网点更新信息。',
          card: null,
        },
        {
          id: 'ebank-phone-change',
          label: '预留手机号变了怎么改',
          asked: '预留手机号变了怎么改？',
          answer:
            '变更预留手机号需要本人携带身份证和银行卡到任意网点柜台办理，出于安全考虑不支持线上变更，也不能代办。',
          card: null,
        },
        {
          id: 'ebank-locked',
          label: '账号被锁定了怎么办',
          asked: '手机银行账号被锁定了怎么办？',
          answer:
            '登录密码连续输错五次会锁定账号，第二天零点自动解锁。也可以在登录页面用短信验证码加身份证件信息自助解锁，或者到网点办理。',
          card: null,
        },
        {
          id: 'ebank-safety',
          label: '怎么防范电信诈骗',
          asked: '怎么防范电信诈骗？',
          answer:
            '我行工作人员绝不会索要你的密码和短信验证码。凡是要求转账到所谓安全账户的，一律是诈骗。如果已经转账，请立即拨打一一〇报警并联系我行冻结账户。',
          card: {
            title: '安全提醒',
            body: ['银行绝不会索要密码或验证码。遇到要求转入「安全账户」的一律是诈骗。'],
            action: null,
          },
        },
      ],
    },
  ],
  en: [
    {
      id: 'account',
      label: 'Accounts',
      blurb: 'Opening, closing, account status',
      icon: '🏦',
      questions: [
        {
          id: 'open-account',
          label: 'How do I open a business account?',
          asked: 'How do I open a business account?',
          answer:
            'You can book a business account opening online, or bring your documents into a branch. Business accounts come in four kinds — basic, general, temporary and special deposit accounts — and each is used for something different.',
          card: {
            title: 'Book a business account opening',
            body: ['Bring the relevant documents to a branch, or submit a booking online. The paperwork differs slightly by account type.'],
            action: 'Tap here to get started',
          },
        },
        {
          id: 'account-basic',
          label: 'What do I need for a basic deposit account?',
          asked: 'What do I need for a basic deposit account?',
          answer:
            'A basic deposit account is your main account for day to day settlement and cash handling, and a company may hold only one. You will need the original business licence, the legal representative’s ID, and the ID and authorisation letter of whoever comes in.',
          card: {
            title: 'Basic deposit account',
            body: ['One per company. Cash withdrawals are allowed.'],
            action: null,
          },
        },
        {
          id: 'account-general',
          label: 'What is a general deposit account for?',
          asked: 'What is a general deposit account for?',
          answer:
            'A general deposit account handles loan disbursement and other settlement flows. You can pay cash in but not draw it out, and you will need a basic deposit account before opening one.',
          card: {
            title: 'General deposit account',
            body: ['Transfers and cash deposits only — no withdrawals. Requires a basic deposit account.'],
            action: null,
          },
        },
        {
          id: 'account-temp',
          label: 'How do I open a temporary deposit account?',
          asked: 'How do I open a temporary deposit account?',
          answer:
            'A temporary deposit account covers temporary operations or work away from your registered address, and runs for at most two years. On top of the usual documents you will need something evidencing how long the work will last.',
          card: {
            title: 'Temporary deposit account',
            body: ['For temporary or off-site operations. Valid for no more than two years.'],
            action: null,
          },
        },
        {
          id: 'account-special',
          label: 'What is a special deposit account used for?',
          asked: 'What is a special deposit account used for?',
          answer:
            'A special deposit account ring-fences money held for a particular purpose — social security funds, housing funds, union dues and the like. You will need documents evidencing what the money is earmarked for.',
          card: {
            title: 'Special deposit account',
            body: ['Ring-fenced funds for a declared purpose. Evidence of that purpose is required.'],
            action: null,
          },
        },
        {
          id: 'account-dormant',
          label: 'My account has gone dormant',
          asked: 'What happens if my account goes dormant?',
          answer:
            'An account with no activity for two running years and nothing owed to the bank is moved to dormant status. To reactivate it, bring your account paperwork and the legal representative’s ID to the branch where it was opened.',
          card: null,
        },
        {
          id: 'account-close',
          label: 'How do I close an account?',
          asked: 'How do I close an account?',
          answer:
            'Clear the balance first, return any unused blank instruments and the account opening licence, then have an authorised person bring their ID and authorisation to the branch. An account with an outstanding loan cannot be closed directly.',
          card: null,
        },
      ],
    },
    {
      id: 'card',
      label: 'Cards',
      blurb: 'Lost cards, PINs, activation',
      icon: '💳',
      questions: [
        {
          id: 'card-lost',
          label: 'I lost my card — how do I report it?',
          asked: 'I lost my card — how do I report it?',
          answer:
            'You can freeze the card in the mobile app straight away, which takes effect immediately. For a formal report and a replacement, bring your ID to any branch and you will walk out with the new card the same day.',
          card: {
            title: 'Freezing and replacing a card',
            body: ['Freeze instantly in the app. A formal report needs you to attend a branch with ID.'],
            action: 'Tap here to freeze the card',
          },
        },
        {
          id: 'card-password',
          label: 'I forgot my PIN',
          asked: 'I forgot my PIN — how do I reset it?',
          answer:
            'Three wrong attempts locks the PIN, and it unlocks by itself at midnight. Resetting it means attending a branch in person with your ID and the card — it cannot be done online or by anyone else on your behalf.',
          card: null,
        },
        {
          id: 'card-activate',
          label: 'How do I activate a new card?',
          asked: 'How do I activate a new card?',
          answer:
            'A card issued over the counter is already active. One posted to you can be activated in the card section of the mobile app with the card number and the code you were sent, or at any branch counter.',
          card: null,
        },
        {
          id: 'card-annual-fee',
          label: 'Are there annual or account fees?',
          asked: 'Are there annual fees or small account management fees?',
          answer:
            'Each customer may hold one account with the annual fee and small balance management fee waived. You can apply for the waiver yourself in the mobile app, or ask at a branch.',
          card: null,
        },
        {
          id: 'card-expired',
          label: 'My card is about to expire',
          asked: 'How do I replace an expiring card?',
          answer:
            'From a month before expiry you can swap it at any branch. The card number stays the same, so your direct debits are unaffected, and there is no charge.',
          card: null,
        },
      ],
    },
    {
      id: 'transfer',
      label: 'Transfers',
      blurb: 'Limits, fees, arrival times',
      icon: '💸',
      questions: [
        {
          id: 'transfer-limit',
          label: 'What are the transfer limits?',
          asked: 'What are the transfer limits?',
          answer:
            'The app defaults to fifty thousand per transfer and two hundred thousand a day. You can raise that yourself in the security centre, or apply at a branch if you need a higher ceiling.',
          card: {
            title: 'Transfer limits',
            body: ['Defaults are 50,000 per transfer and 200,000 per day. Adjustable in the security centre.'],
            action: null,
          },
        },
        {
          id: 'transfer-fee',
          label: 'What do transfers cost?',
          asked: 'What are the transfer fees?',
          answer:
            'Domestic transfers through the app and online banking are currently free. Over the counter, an interbank transfer costs five basis points of the amount, with a two yuan minimum and a fifty yuan cap.',
          card: null,
        },
        {
          id: 'transfer-arrival',
          label: 'How long does a transfer take?',
          asked: 'How long does a transfer take to arrive?',
          answer:
            'Transfers within our bank are instant. Interbank you can choose instant, standard or next day — instant usually lands within minutes, standard within about two hours. A next-day transfer can be cancelled before it settles.',
          card: {
            title: 'Arrival times',
            body: ['Instant within our bank; interbank can be instant, standard or next day.'],
            action: null,
          },
        },
        {
          id: 'transfer-wrong',
          label: 'I sent money to the wrong account',
          asked: 'I sent money to the wrong account — what now?',
          answer:
            'If you chose next-day settlement you can cancel it yourself in the app before it lands. Once the money has arrived the bank has no authority to pull it back, so you would need to ask the recipient to return it, and failing that pursue it legally.',
          card: null,
        },
        {
          id: 'transfer-overseas',
          label: 'How do I send money abroad?',
          asked: 'How do I send money abroad?',
          answer:
            'Overseas remittances are handled in branch in person, with your ID and evidence of what the money is for. Individuals have an annual facilitation quota equivalent to fifty thousand US dollars; beyond that you will need supporting documentation.',
          card: null,
        },
      ],
    },
    {
      id: 'loan',
      label: 'Lending',
      blurb: 'Rates, applications, repayment',
      icon: '📄',
      questions: [
        {
          id: 'loan-rate',
          label: 'What are your loan rates?',
          asked: 'What are your loan rates?',
          answer:
            'Rates move with the product, the term and your own credit position. Home loans currently price off the latest LPR plus a margin, and the rate you actually get is set once your application has been assessed.',
          card: {
            title: 'Loan rates',
            body: ['Priced off LPR plus a margin. Your actual rate is confirmed at approval.'],
            action: 'Tap here for details',
          },
        },
        {
          id: 'loan-apply',
          label: 'How do I apply for a mortgage?',
          asked: 'How do I apply for a mortgage?',
          answer:
            'Start with an indicative amount in the app, then bring your ID, proof of income, the purchase contract and evidence of your deposit to a branch. Assessment usually takes five to ten working days once everything is in.',
          card: {
            title: 'What to bring',
            body: ['ID, proof of income, purchase contract, evidence of deposit. Around 5 to 10 working days.'],
            action: 'Tap here for an indicative amount',
          },
        },
        {
          id: 'loan-prepay',
          label: 'Can I repay early?',
          asked: 'Can I repay my loan early?',
          answer:
            'Yes. After the first year there is no early repayment charge; inside the first year it is one percent of the amount repaid. Book it in the app or arrange it at the branch that handled the loan.',
          card: null,
        },
        {
          id: 'loan-overdue',
          label: 'What happens if I miss a payment?',
          asked: 'What happens if I miss a loan payment?',
          answer:
            'A missed payment attracts penalty interest and is reported to the credit reference system, which affects future lending and card applications. If it is a short-term cash flow problem, contact the branch that handled the loan as early as you can to discuss the arrangements.',
          card: null,
        },
      ],
    },
    {
      id: 'wealth',
      label: 'Investments',
      blurb: 'Products, risk profile, redemption',
      icon: '📈',
      questions: [
        {
          id: 'wealth-risk',
          label: 'How does the risk assessment work?',
          asked: 'How does the investment risk assessment work?',
          answer:
            'The risk tolerance assessment is done in the app — about ten questions, a few minutes. The result is valid for a year, after which you will need to retake it before buying investment products.',
          card: null,
        },
        {
          id: 'wealth-products',
          label: 'What investment products do you have?',
          asked: 'What investment products do you have?',
          answer:
            'Our products are graded across five risk levels and span cash management, fixed income, mixed and equity strategies. Which ones you can buy depends on the outcome of your risk assessment.',
          card: {
            title: 'Investment products',
            body: ['Five risk grades. A completed risk assessment is required first.'],
            action: 'Tap here to browse',
          },
        },
        {
          id: 'wealth-redeem',
          label: 'How do I redeem an investment?',
          asked: 'How do I redeem an investment?',
          answer:
            'Open-ended products can be redeemed in the app on any dealing day, with the money back within one to three working days. Closed-ended products run to maturity and cannot be redeemed early.',
          card: null,
        },
        {
          id: 'wealth-deposit',
          label: 'What are your deposit rates?',
          asked: 'What are your fixed deposit rates?',
          answer:
            'Fixed deposit rates are banded by term, and the current board rates are in the deposits section of the app. Certificates of deposit generally pay more than an equivalent fixed deposit, with a two hundred thousand minimum.',
          card: null,
        },
      ],
    },
    {
      id: 'ebank',
      label: 'Digital banking',
      blurb: 'App, online banking, security',
      icon: '📱',
      questions: [
        {
          id: 'ebank-register',
          label: 'How do I set up the app?',
          asked: 'How do I set up mobile banking?',
          answer:
            'Download our app and register with your card and the mobile number we hold for you. If we have no number on file, or yours has changed, you will need to update it at a branch first.',
          card: null,
        },
        {
          id: 'ebank-phone-change',
          label: 'My mobile number has changed',
          asked: 'How do I change the mobile number on my account?',
          answer:
            'Changing the number we hold has to be done in person at a branch with your ID and card. For security it cannot be done online, and nobody can do it on your behalf.',
          card: null,
        },
        {
          id: 'ebank-locked',
          label: 'My login is locked',
          asked: 'My mobile banking login is locked — what do I do?',
          answer:
            'Five wrong passwords locks the login, and it clears by itself at midnight. You can also unlock it yourself on the login screen using an SMS code and your ID details, or come into a branch.',
          card: null,
        },
        {
          id: 'ebank-safety',
          label: 'How do I avoid scams?',
          asked: 'How do I protect myself from scams?',
          answer:
            'Our staff will never ask for your password or an SMS code. Anyone telling you to move money to a so-called safe account is running a scam. If you have already sent money, call the police immediately and contact us to freeze the account.',
          card: {
            title: 'Staying safe',
            body: ['We never ask for passwords or codes. Any request to move money to a "safe account" is a scam.'],
            action: null,
          },
        },
      ],
    },
  ],
}

export function categories(lang: Lang): ServiceCategory[] {
  return CATEGORIES[lang]
}


/** Every question across every category, for the search box to match against. */
export function allQuestions(lang: Lang): ServiceQuestion[] {
  return CATEGORIES[lang].flatMap((c) => c.questions)
}
