package com.ape.smarthedge_risk_hub.ai.chat;

import java.util.Map;

/**
 * 流式对话的事件回调。
 *
 * <p>ChatService 只依赖本接口，由 Controller 适配成 SSE 推给前端；
 * 这样编排逻辑不耦合任何 Web 类型（SseEmitter / HttpServletResponse），
 * 单测里可以直接塞一个记录事件的假实现。
 */
public interface StreamListener {

    /** 模型决定调用某个工具（执行前）。前端可据此先展示「正在查询…」 */
    void onToolStart(int round, String tool, Map<String, String> args);

    /**
     * 工具执行完成。
     *
     * @param result 回灌给模型的原始 JSON 文本；失败时是可读的错误说明
     */
    void onToolEnd(int round, String tool, String result, long costMs);

    /** 最终答案的增量片段（可能为空串，调用方需自行忽略） */
    void onDelta(String text);

    /** 正常结束 */
    void onDone(ChatService.ChatResult result);

    /** 异常结束，message 已处理成可读文案 */
    void onError(String message);
}
