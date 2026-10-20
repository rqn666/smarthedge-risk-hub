package com.ape.smarthedge_risk_hub.ai.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 根路径兜底：访问 http://localhost:8080/ 时给入口提示，
 * 不再落到 Whitelabel Error Page。
 */
@RestController
public class RootController {

    @GetMapping(value = "/", produces = "text/plain;charset=UTF-8")
    public String index() {
        return "SmartHedge AI 已就绪。可用入口：\n"
                + "  GET  /api/ai/help              接口清单\n"
                + "  GET  /api/ai/ask?message=xxx   浏览器直接提问\n"
                + "  POST /api/ai/chat              body: {\"message\":\"xxx\"}\n"
                + "  GET  /api/ai/prompt/reload     热加载系统提示词\n";
    }
}
