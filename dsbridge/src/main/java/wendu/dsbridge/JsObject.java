package wendu.dsbridge;


import android.webkit.JavascriptInterface;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

class JsObject {
    private final String namespace;
    private final Object obj;
    private final Class<?> clazz;
    private final Map<String, Method> syncMethods = new HashMap<>();
    private final Map<String, Method> asyncMethods = new HashMap<>();

    JsObject(String namespace, Object obj) {
        this.namespace = namespace;
        this.obj = obj;
        this.clazz = obj.getClass();
        cacheMethods();
    }

    private void cacheMethods() {
        Method[] methods = this.clazz.getMethods();
        for(Method m : methods){
            if(android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.JELLY_BEAN_MR1){
                JavascriptInterface annotation = m.getAnnotation(JavascriptInterface.class);
                if(annotation==null){
                    continue;
                }
            }
            try {
                Class<?>[] paramTypes = m.getParameterTypes();
                if(paramTypes.length==1){
                    Class<?> paramClazz = paramTypes[0];
                    if (paramClazz.isAssignableFrom(Object.class)){
                        m.setAccessible(true);
                        syncMethods.put(m.getName(),m);
                    }
                }
                else if(paramTypes.length==2){
                    if(paramTypes[0].isAssignableFrom(Object.class)
                            &&paramTypes[1].isAssignableFrom(CompletionHandler.class)) {
                        m.setAccessible(true);
                        asyncMethods.put(m.getName(), m);
                    }
                }
            } catch (Exception ex) {
                ex.printStackTrace();
            }
        }
    }

    boolean hasMethod(String name, boolean isAsync){
        if(isAsync){
            return asyncMethods.get(name)!=null;
        }
        else{
            return syncMethods.get(name)!=null;
        }
    }
    Method getMethod(String name, boolean isAsync){
        if(isAsync){
            return asyncMethods.get(name);
        }
        else {
            return syncMethods.get(name);
        }
    }
    Object getObject(){
        return this.obj;
    }

    String getNamespace() {
        return namespace;
    }
}
