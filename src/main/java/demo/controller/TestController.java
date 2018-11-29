package demo.controller;

import demo.service.TestService;
import org.lizi.framework.annotation.Autowirted;
import org.lizi.framework.annotation.Controller;
import org.lizi.framework.annotation.RequestMapping;
import org.lizi.framework.annotation.RequestParam;


@Controller
@RequestMapping("/test")
public class TestController {

    @Autowirted
    private TestService service;

    @RequestMapping("/test")
    public String test(@RequestParam("aaaa") String aaa){
        return service.getData();
    }
}
