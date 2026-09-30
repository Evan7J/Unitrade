import request from '../utils/request'

/**
 * 议价接口封装。
 *
 * 注意 messageId 的处理：它是服务端的幂等键。
 * 调用方必须在"用户点一次发送"时生成一次，**请求失败重试时要复用同一个**，
 * 否则服务端无法区分"重试"和"两次真实出价"。
 */

/** 卖家：为商品配置议价底价 */
export const setAuthorization = (productId, floorPrice) =>
  request.put(`/negotiation/authorization/${productId}`, { floorPrice })

/** 买家：发起议价（若已有会话则直接返回当前状态） */
export const start = (productId) => request.post(`/negotiation/start/${productId}`)

/** 买家：出价 */
export const offer = (sessionNo, payload) =>
  request.post(`/negotiation/${sessionNo}/offer`, payload)

/** 买家：接受当前报价 */
export const accept = (sessionNo, messageId) =>
  request.post(`/negotiation/${sessionNo}/accept`, { messageId })

/** 查询会话状态 */
export const state = (sessionNo) => request.get(`/negotiation/${sessionNo}`)

/** 查询轮次历史（可追溯的议价记录） */
export const rounds = (sessionNo) => request.get(`/negotiation/${sessionNo}/rounds`)
