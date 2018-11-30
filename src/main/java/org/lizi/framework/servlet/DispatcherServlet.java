package org.lizi.framework.servlet;

import com.google.gson.Gson;
import org.lizi.framework.annotation.*;

import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class DispatcherServlet extends HttpServlet {


    private Properties p = new Properties();

    private List<String> classNames = new ArrayList<String>();

    private Map<String,Object> iocs = new HashMap<String,Object>();

    private List<Handler> handlerMapping = new ArrayList<Handler>();
    public void init(ServletConfig config) throws ServletException {
        // 1 加载配置文件
        // 获取配置文件所在路径 并通过配置文件的路径将文件读取进来
        String path = config.getInitParameter("contextConfigLocation");
        doLoadConfig(path);
        // 2 读取配置文件扫描相关的类
        //通过获取到的配置文件 取出其中需要扫描的路径
        doScanner(p.getProperty("scanPackage"));
        // 3将已经扫描的类进行初始化并放入ioc容器中
        doInstance();
        // 4依赖注入
        doAutowired();
        // 5初始化handlerMapping
        initHandlerMapping();
        System.out.println("this is My Spring servlet");
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        this.doPost(req, resp);
    }
    // 6等待调用

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        try{
            doDispatcher(req, resp);
        }catch(Exception e){
            resp.getWriter().write("500 Exception,Details:\r\n"+Arrays.toString(e.getStackTrace()));
        }
    }
    private void doDispatcher(HttpServletRequest req, HttpServletResponse resp) {

        try{
            Handler handler = getHandler(req);
            if(handler == null){
                resp.getWriter().write("404 Not Found");
                return;
            }
            // 获取参数列表
            Class<?>[] paramTypes = handler.method.getParameterTypes();
            // 保存需要自动赋值的参数值
            Object[] paramValues = new Object[paramTypes.length];
            Map<String,String[]> params = req.getParameterMap();
            for (Map.Entry<String,String[]> param : params.entrySet()) {
                String value = Arrays.toString(param.getValue()).replaceAll("\\[|\\]", "").replaceAll(",\\s", ",");
                // 如果找不到匹配的对象，则开始填充参数值
                if(!handler.paramIndexMapping.containsKey(param.getKey())){
                    continue;
                }
                int index = handler.paramIndexMapping.get(param.getKey());
                paramValues[index] = convert(paramTypes[index],value);
            }

            // 设置方法中的request和response对象
            Integer reqIndex = handler.paramIndexMapping.get(HttpServletRequest.class.getName());
            Integer respIndex =  handler.paramIndexMapping.get(HttpServletResponse.class.getName());
            if (reqIndex != null){
                paramValues[reqIndex]= req;
            }
            if (respIndex != null) {
                paramValues[respIndex]= resp;
            }

            Object result = handler.method.invoke(handler.controller,paramValues );
            resp.getWriter().write(new Gson().toJson(result));
        }catch(Exception e){
            System.err.println(e.getStackTrace());
            e.printStackTrace();
        }

    }

    private Handler getHandler(HttpServletRequest req) throws Exception{
        if(handlerMapping.isEmpty()) return null;
        String url = req.getRequestURI();
        String contextPath = req.getContextPath();
        url = url.replace(contextPath, "").replaceAll("/+", "/");
        for (Handler handler : handlerMapping) {
            try{
                Matcher matcher = handler.pattern.matcher(url);
                // 如果没有匹配上继续下一个匹配
                if(!matcher.matches()){
                    continue;
                }
                return handler;
            }catch(Exception e){
                throw e;
            }
        }
        return null;
    }

    private Object convert(Class<?> type,String value){
        if(Integer.class == type){
            return Integer.valueOf(value);
        }
        return value;
    }

    private void  doLoadConfig(String path) {
        // 根据路径获取一个InputStream的流
        InputStream is = this.getClass().getClassLoader().getResourceAsStream(path);
        try {
            p.load(is);
        } catch (Exception e) {
            e.printStackTrace();
        }finally {
            // 判断是否为空 如果不为空 则close流
            if(null != is)
                try {
                    is.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
        }
    }

    /**
     * 找出所有的文件
     * @param packageName
     */
    private void doScanner(String packageName) {
        String  filePath = this.getClass().getClassLoader().getResource("/"+packageName.replaceAll("\\.", "/")).getFile();

        File files = new File(filePath);

        for (File f : files.listFiles()) {

            if (f.isDirectory()) {
                doScanner(packageName+"."+f.getName());
            }else {
                String className = packageName+"."+f.getName().replace(".class", "");

                classNames.add(className);
            }
        }
    }

    private void doInstance() {

        if(classNames.isEmpty()) {
            return;
        }

        try {
            for (String className : classNames) {
                Class<?> clazz = Class.forName(className);
                // 初始化这里需要注意几个概念
                // 对于controller来说 默认情况 一般是 首字母小写 同时我们也需要注意一点，在并不是所有的controller都会被注入。
                // 注释类型的注释存在于此元素上，则返回true，否则返回false
                if(clazz.isAnnotationPresent(Controller.class)) {
                    String beanName = firstLower(clazz.getSimpleName());
                    if(!iocs.containsKey(beanName)) {
                        iocs.put(beanName, clazz.newInstance());
                    }
                }else if(clazz.isAnnotationPresent(Service.class)) {
                    // 1 IOC容器的结构 Map<String,Object>
                    // String 也可以叫做IOC容器的beanName
                    // beanName有几个规则 默认情况下是类名的首字母小写
                    // 第二种情况 就是当我们自定义了一个名称时应该以我们自定义的优先级高 或者是说以我们自定义的为准
                    // 第三种情况 当我们注入service时我们一般是注入的接口，在java中接口是不能被实例化的。这时我们需要主要接口的实现类
                    // 这时我们又会遇到另外一个问题 就是接口是可以实现多个的，所以这时候我们应该根据接口的类型来注入。
                    // 首先我们取出自定义的
                    Service service = clazz.getAnnotation(Service.class);
                    //获取自定义的name
                    String beanName = service.value();
                    // 判断是否为空
                    if("".equals(beanName)) {
                        // 默认首字母小写
                        beanName = firstLower(clazz.getSimpleName());
                    }
                    // 这里是将第一种和第二种情况处理
                    Object instance = clazz.newInstance();
                    iocs.put(beanName, instance);
                    // 处理第三种情况 获取接口类型
                    Class<?> [] classes = clazz.getInterfaces();
                    for (Class<?> c : classes) {
                        // 这里为什么这样可以 因为instance在这里是单例模式 只被初始化一次
                        iocs.put(c.getName(), instance);
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * 依赖注入
     */
    private void doAutowired() {
        // 判断是否为空 如果为空就直接退出
        if(iocs.isEmpty()) return;
        // 不为空就循环取出
        for (Map.Entry<String, Object> entry : iocs.entrySet()) {
            // 通过反射取出entry中所有的字段
            Field[] fields = entry.getValue().getClass().getDeclaredFields();
            // 循环赋值
            for (Field field : fields) {
                // 这里需要注意 只有加了Autowire才进行赋值
                if(!field.isAnnotationPresent(Autowire.class)) {
                    continue;
                }

                // 如果有获取它的值
                Autowire autowrited = field.getAnnotation(Autowire.class);
                String beanName = autowrited.value();
                // 判断beanName是否设置了
                if("".equals(beanName)) {
                    // 从field中取
                    beanName = field.getType().getName();
                }
                // 这里需要注意 因为我们的字段可能加了访问权限 如受保护的 私有的 ...
                // 这里我们需要设置不管什么访问权限全部获取 授权
                field.setAccessible(true);
                // 赋值
                try {
                    //第一个参数表示给哪个字段赋值，第二个参数要赋的值
                    field.set(entry.getValue(), iocs.get(beanName));
                } catch (Exception e) {
                    e.printStackTrace();
                    continue;
                }
            }
        }
    }

    /**
     * 初始化handlerMapping
     */
    private void initHandlerMapping() {
        if(iocs.isEmpty()){
            return;
        }
        for (Map.Entry<String, Object> entry : iocs.entrySet()) {
            Class<?> clazz = entry.getValue().getClass();
            // 这里因为handlerMapping对应的是controller所以这里需要判断是不是controller
            if(!clazz.isAnnotationPresent(Controller.class)){
                continue;
            }
            // 保存url
            String url = "";
            // 取出RequestMapping中的值
            if(clazz.isAnnotationPresent(RequestMapping.class)) {
                RequestMapping requestMapping = clazz.getAnnotation(RequestMapping.class);
                url = requestMapping.value();
            }
            // 扫描所有方法 这里需要注意一点为什么不用clazz.getDeclaredMethods() 因为这个只是扫描所有方法 而这里我们只是需要公有方法即可，减少循环次数

            Method[]  methods = clazz.getMethods();
            for (Method method : methods) {
                if(!method.isAnnotationPresent(RequestMapping.class)){
                    continue;
                }

                // 映射URL
                RequestMapping requestMapping = method.getAnnotation(RequestMapping.class);
                String regex = ("/"+url+ requestMapping.value()).replaceAll("/+", "/");
                Pattern pattern = Pattern.compile(regex);
                handlerMapping.add(new Handler(pattern, entry.getValue(), method));
                System.out.println("add Mapping   "+regex+","+method);
            }
        }
    }


    /**
     * 首字母变成小写
     * @param name
     * @return
     */
    private static String firstLower(String name) {
        char[] chars = name.toCharArray();
        chars[0]+=32;
        return String.valueOf(chars);
    }

    /**
     * handler的内部类
     */
    private class Handler{

        protected Object controller;
        /**
         * 保存方法对应的实例
         */
        protected Method method;
        /**
         * 保存映射的方法
         */
        protected Pattern pattern;
        /**
         * 参数对应顺序
         */
        protected Map<String,Integer> paramIndexMapping;

        protected Handler(Pattern pattern,Object controller,Method method){
            this.controller = controller;
            this.pattern = pattern;
            this.method = method;
            paramIndexMapping = new HashMap<String, Integer>();
            putParamIndexMapping(method);
        }
        private void putParamIndexMapping(Method method){
            // 提取方法中加了注解的参数
            Annotation[][] pa = method.getParameterAnnotations();
            for(int i=0;i<pa.length;i++){
                for (Annotation a : pa[i]) {
                    if(a instanceof RequestParam){
                        String paramName = ((RequestParam)a).value();
                        if(!"".equals(paramName.trim())){
                            paramIndexMapping.put(paramName, i);
                        }
                    }
                }
            }

            Class<?>[] paramsTypes = method.getParameterTypes();
            for(int i=0;i<paramsTypes.length;i++){
                Class<?> type = paramsTypes[i];
                if(type == HttpServletRequest.class ||
                        type == HttpServletResponse.class){
                    paramIndexMapping.put(type.getName(), i);
                }
            }
        }
    }
    
}
