package demo.service;

import org.lizi.framework.annotation.Service;

@Service
public class TestServiceImpl implements TestService {
    @Override
    public String getData() {
        return "this is Test service";
    }
}
