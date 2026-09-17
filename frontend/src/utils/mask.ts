/**
 * 脱敏工具（8.5 / NFR-SE-04）。
 *
 * 与后端 `MaskUtils` 规则同源；模板中禁止散落字符串截取，敏感字段展示一律经此处理。
 */

const MASK = '****'

/** 保留头尾的通用打码 */
function keepHeadAndTail(value: string | null | undefined, head: number, tail: number): string {
  if (value === null || value === undefined || value.trim() === '') {
    return ''
  }
  if (value.length <= head + tail) {
    return MASK
  }
  return `${value.slice(0, head)}${MASK}${value.slice(value.length - tail)}`
}

/** openid 脱敏：前 4 + **** + 后 4（BR-21） */
export function maskOpenid(openid: string | null | undefined): string {
  return keepHeadAndTail(openid, 4, 4)
}

/** 运单号脱敏：形如 SF12****7890（BR-15） */
export function maskTrackingNo(trackingNo: string | null | undefined): string {
  return keepHeadAndTail(trackingNo, 4, 4)
}

/** 手机号脱敏：前 3 + **** + 后 4 */
export function maskPhone(phone: string | null | undefined): string {
  return keepHeadAndTail(phone, 3, 4)
}

/** 密钥脱敏：仅保留尾 4 位，形如 ****ab12 */
export function maskSecret(value: string | null | undefined): string {
  if (value === null || value === undefined || value === '') {
    return ''
  }
  if (value.length <= 4) {
    return MASK
  }
  return `${MASK}${value.slice(value.length - 4)}`
}
