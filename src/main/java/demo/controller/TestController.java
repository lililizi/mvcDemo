package demo.controller;

import demo.service.TestService;
import org.lizi.framework.annotation.Autowirted;
import org.lizi.framework.annotation.Controller;
import org.lizi.framework.annotation.RequestMapping;
import org.lizi.framework.annotation.RequestParam;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

@Controller
@RequestMapping("/test")
public class TestController {

    @Autowirted
    private TestService service;

    @RequestMapping("/test")
    public String test(HttpServletRequest request, HttpServletResponse response, @RequestParam("aaaa") String aaa){
        return service.getData();
    }
}
