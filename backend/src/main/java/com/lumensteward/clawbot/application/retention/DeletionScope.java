package com.lumensteward.clawbot.application.retention;

/**
 * 用户数据删除范围（FR-19 ② 细粒度删除）。
 */
public enum DeletionScope {
    /** 全部个人信息（对话 + 档案 + 账户锚点匿名化）。 */
    ALL,
    /** 仅对话记录（消息 + 会话）。 */
    CHAT,
    /** 仅宠物档案。 */
    PET
}
