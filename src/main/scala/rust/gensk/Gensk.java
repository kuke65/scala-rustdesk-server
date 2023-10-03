package rust.gensk;

import jnr.ffi.Platform;
import test.HelloWorld;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Base64;
import java.util.Optional;
import java.util.Vector;

/**
 * @Author Shuheng.Zhang
 * 2023/9/5 16:31
 */
public class Gensk {

    public native String sk(String input);
    public native String sk2(String input);
    public native String sign(String m, String sk);
    public native String addrencode(String addr);
    public native String addrdecode(String key);
    public native String bytesencode(String bytes);
    public native String bytesdecode(String bytes);

    private static Gensk instance = new Gensk();

    private Gensk() {
        String videcodecPlatform = "gensk";
        int ordinal = Platform.getNativePlatform().getOS().ordinal();
        if (ordinal != Platform.OS.WINDOWS.ordinal()) {
            videcodecPlatform = "libgensk";
            String libpath = System.getProperty("java.library.path");
            String suffix = System.getProperty("java.library.suffix");
            System.load(libpath + videcodecPlatform + suffix);
        }else {
            // 只能通过反射类判断是否加载
            String libpath = new File("").getAbsolutePath() + "/lib/";
            String suffix = ".dll";
            System.load(libpath + videcodecPlatform + suffix);
            //System.loadLibrary(stl2imagePlatform);
        }


    }

    public static Gensk getInstance() {
        return instance;
    }

    @Override
    public void finalize() throws Throwable {
        String stl2imagePlatform = "gensk";
        int ordinal = Platform.getNativePlatform().getOS().ordinal();
        if (ordinal != Platform.OS.WINDOWS.ordinal()) {
            stl2imagePlatform = "libgensk";
        }
        getInstance().removeNativeLibraries(getInstance().getByNativeLibraries(stl2imagePlatform));
    }

    private void removeNativeLibraries(Optional optional) throws IllegalAccessException, InvocationTargetException {
        if(optional.isPresent()) {
            Object o = optional.get();
            Class<?>[] classes = ClassLoader.class.getDeclaredClasses();
            for (int i = 0; i < classes.length; i++) {
                if("NativeLibrary".equals(classes[i].getSimpleName())) {
                    //o = classes[i].newInstance();
                    /*Constructor<?> declaredConstructor = classes[i].getDeclaredConstructor(Class.class, String.class, boolean.class);
                    declaredConstructor.setAccessible(true);
                    o = declaredConstructor.newInstance(test.HelloWorld.getInstance().getClass(), libName, false);

                    Field loaded = classes[i].getDeclaredField("loaded");
                    loaded.setAccessible(true);
                    loaded.setBoolean(o, true);*/

                    Method[] methods = classes[i].getDeclaredMethods();
                    for (int j = 0; j < methods.length; j++) {
                        if("finalize".equals(methods[j].getName())) {
                            methods[j].setAccessible(true);
                            methods[j].invoke(o);
                            methods[j].setAccessible(false);
                        }
                    }
                }
            }
        }else {
            System.out.println("not doing : test.gensk.removeNativeLibraries optional is empty ");
        }

    }

    private Optional getByNativeLibraries(String libName) throws NoSuchFieldException, IllegalAccessException {
        Field field = ClassLoader.class.getDeclaredField("nativeLibraries");
        field.setAccessible(true);
        Vector<Object> v = (Vector<Object>) field.get(HelloWorld.getInstance().getClass().getClassLoader());
        for (int i = 0; i < v.size(); i++) {
            Field name = v.get(i).getClass().getDeclaredField("name");
            name.setAccessible(true);
            String libNativeName = name.get(v.get(i)).toString();
            if(libNativeName.contains(libName)) {
                return Optional.of(v.get(i));
            }
        }
        return Optional.empty();
    }


}
