package com.qwenpaw.controller.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 管理页面入口控制器。
 */
@Controller
public class IndexController {

    /**
     * 将根路径转发到静态管理页面。
     */
    @GetMapping("/")
    public String index() {
        return "forward:/index.html";
    }
}
