package com.github.obhen233.compiler.util;


import com.github.obhen233.compiler.constant.Constants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * @author: obhen233
 * @date: 2027/04/27
 * desc:  自定义classLoader,用来加载动态编译好的CLASS文件
 */
public class ClassClassLoader extends ClassLoader {
    private static final Logger log = LoggerFactory.getLogger(ClassClassLoader.class);

    private String path = Constants.classPath;

    public ClassClassLoader(ClassLoader parent) {
        super(parent);
    }

    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException {
        // 验证类名格式，防止路径穿越
        if (name == null || !name.matches("[a-zA-Z_$][a-zA-Z0-9_$]*(\\.[a-zA-Z_$][a-zA-Z0-9_$]*)*")) {
            log.warn("Invalid class name: {}", name);
            return null;
        }

        // 将包名转为文件路径
        String classPath = name.replace(".", File.separator) + ".class";
        String classFile = path + classPath;

        // 安全检查：确保最终路径在工作区内
        Path resolvedPath;
        try {
            resolvedPath = Paths.get(classFile).toAbsolutePath().normalize();
            Path basePath = Paths.get(path).toAbsolutePath().normalize();
            if (!resolvedPath.startsWith(basePath)) {
                log.warn("Class file path escapes workspace: {}", classFile);
                return null;
            }
        } catch (Exception e) {
            log.warn("Failed to resolve class file path: {}", classFile);
            return null;
        }

        Class<?> clazz = null;
        try {
            byte[] data = getClassFileBytes(classFile);
            clazz = defineClass(name, data, 0, data.length);
        } catch (Exception e) {
            log.debug("Failed to load class {}: {}", name, e.getMessage());
        }
        return clazz;
    }

    private byte[] getClassFileBytes(String classFile) throws Exception {
        // 采用NIO读取
        try (FileInputStream fis = new FileInputStream(classFile);
             FileChannel fileC = fis.getChannel()) {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            WritableByteChannel outC = Channels.newChannel(baos);
            ByteBuffer buffer = ByteBuffer.allocateDirect(1024);
            while (true) {
                int i = fileC.read(buffer);
                if (i == 0 || i == -1) {
                    break;
                }
                buffer.flip();
                outC.write(buffer);
                buffer.clear();
            }
            return baos.toByteArray();
        }
    }
}
