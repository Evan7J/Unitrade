package com.example.unitrade.controller;

import com.example.unitrade.agent.AgentReply;
import com.example.unitrade.agent.AgentService;
import com.example.unitrade.common.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;

import java.util.Map;

@RestController
@RequestMapping("/api/agent")
@RequiredArgsConstructor
public class AgentChatController {

    private final AgentService agentService;

    @PostMapping("/chat")
    public Result<AgentReply> chat(@RequestBody Map<String, String> request) {
        String message = request.getOrDefault("message", "");
        String sessionId = request.getOrDefault("sessionId", null);
        return Result.success(agentService.chat(sessionId, message));
    }

    /**
     * 流式对话：SSE 逐字推送文本，结束时追加一个 __META__ 事件携带草稿/商品结果。
     */
    @PostMapping("/chat/stream")
    public SseEmitter streamChat(@RequestBody Map<String, String> request) {
        String message = request.getOrDefault("message", "");
        String sessionId = request.getOrDefault("sessionId", null);

        SseEmitter emitter = new SseEmitter(0L);
        Flux<String> flux = agentService.streamText(sessionId, message);
        flux.subscribe(
                data -> send(emitter, data),
                err -> completeWithError(emitter, err),
                () -> complete(emitter));
        return emitter;
    }

    private void send(SseEmitter emitter, String data) {
        try {
            emitter.send(SseEmitter.event().data(data));
        } catch (Exception e) {
            completeWithError(emitter, e);
        }
    }

    private void complete(SseEmitter emitter) {
        try {
            emitter.complete();
        } catch (Exception ignored) {
        }
    }

    private void completeWithError(SseEmitter emitter, Throwable err) {
        try {
            emitter.completeWithError(err);
        } catch (Exception ignored) {
        }
    }
}