package com.tvmedia.openlist.quark

/**
 * 夸克协议层错误：网络失败、业务 `code != 0`、响应结构不符预期、凭证失效。
 *
 * 消息必须是**可读的、能直接显示给用户**的（UI 会把它当作错误文案），
 * 且**不得包含 token / cookie 内容**。
 */
class QuarkProtocolException(message: String, cause: Throwable? = null) : Exception(message, cause)
