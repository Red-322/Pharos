package com.pharos.rag.assistant.model.vo.chat;

import com.pharos.rag.assistant.model.enums.AssistantToolMode;
import com.pharos.rag.qa.model.vo.AskQuestionResponse;

import java.util.List;

public record AssistantChatResponse(
        /**
         * 会话ID
         */
        Long sessionId,
        /**
         * 助手回复消息的消息ID
         */
        Long messageId,
        /**
         * 助手回复的文本内容
         */
        String reply,
        /**
         * 当前对话的工具模式
         */
        AssistantToolMode toolMode,
        /**
         * 知识库组ID（仅 KB_SEARCH 模式有值）
         */
        Long groupId,
        /**
         * 知识库引用来源列表
         */
        List<AskQuestionResponse.Citation> citations
) {
}
