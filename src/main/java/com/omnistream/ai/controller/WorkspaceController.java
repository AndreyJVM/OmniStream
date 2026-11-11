package com.omnistream.ai.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class WorkspaceController {
    @org.springframework.web.bind.annotation.ResponseBody
    @GetMapping("/workspace-config")
    public java.util.Map<String,Boolean> configuration(
        @org.springframework.beans.factory.annotation.Value("${omnistream.public-workspace:false}") boolean publicWorkspace) {
        return java.util.Map.of("publicWorkspace", publicWorkspace);
    }

    @GetMapping({"/workspace", "/workspace/"})
    public String workspace() {
        return "forward:/workspace.html";
    }
}
