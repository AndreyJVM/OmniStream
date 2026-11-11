package com.omnistream.ai.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class WorkspaceController {
    @GetMapping({"/workspace", "/workspace/"})
    public String workspace() {
        return "forward:/workspace.html";
    }
}
