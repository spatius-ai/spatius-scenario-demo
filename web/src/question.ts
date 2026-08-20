/**
 * The question model and question bank. Same content as the Android and iOS clients —
 * changes have to be made in all three.
 */
import { computed } from 'vue'
import { lang, t, type Lang } from './i18n'

export interface Question {
  /** The question text */
  stem: string
  /** Option texts, in A/B/C/D order */
  options: string[]
  /** Index of the correct option */
  answerIndex: number
  /** The topic this question belongs to, shown above the stem */
  topic: string
}

export function optionLabel(index: number): string {
  return String.fromCharCode('A'.charCodeAt(0) + index)
}

/**
 * Assembles the full text for the avatar to read aloud. The phrasing differences
 * (Chinese 「第一题」 vs English "Question 1") live in i18n; this only picks the
 * current language's version.
 */
export function readAloudText(q: Question, number: number): string {
  return t.value.readAloud(q.stem, q.options, number)
}

/**
 * One question bank per language: switching languages swaps whole questions rather
 * than wrapping a Chinese stem in an English shell. The count and the answer indices
 * match across both, so answers already given do not shift when switching.
 */
const QUESTIONS: Record<Lang, Question[]> = {
  zh: [
    {
      topic: '数与代数',
      stem: '小明买了 3 本练习册和 1 支钢笔，一共花了 47 元。已知钢笔的单价是 11 元，那么每本练习册多少元？',
      options: ['10 元', '12 元', '14 元', '16 元'],
      answerIndex: 1,
    },
    {
      topic: '图形与几何',
      stem: '一个三角形的两个内角分别是 55° 和 65°，那么第三个内角的度数是多少？',
      options: ['50°', '60°', '70°', '80°'],
      answerIndex: 1,
    },
  ],
  en: [
    {
      topic: 'Numbers and algebra',
      stem: 'Sam bought 3 workbooks and 1 pen for $47 in total. The pen costs $11. How much does each workbook cost?',
      options: ['$10', '$12', '$14', '$16'],
      answerIndex: 1,
    },
    {
      topic: 'Shape and geometry',
      stem: 'Two interior angles of a triangle measure 55° and 65°. What is the third angle?',
      options: ['50°', '60°', '70°', '80°'],
      answerIndex: 1,
    },
  ],
}

export const SAMPLE_QUESTIONS = computed(() => QUESTIONS[lang.value])
