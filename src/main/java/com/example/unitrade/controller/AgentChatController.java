package com.example.unitrade.controller;

import com.example.unitrade.agent.AgentReply;
import com.example.unitrade.agent.AgentService;
import com.example.unitrade.common.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

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
}